#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <malloc.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Android Bionic / Scudo mallopt extensions (defined in <malloc.h> on API 29+/31+) */
#ifndef M_DECAY_TIME
#define M_DECAY_TIME (-100)
#endif
#ifndef M_PURGE
#define M_PURGE (-101)
#endif

#if defined(__aarch64__)
#include <asm/hwcap.h>
#ifndef HWCAP_ASIMD
#define HWCAP_ASIMD (1UL << 1)
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP_SVE
#define HWCAP_SVE (1UL << 22)
#endif
#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2 (1UL << 1)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif
#endif

static pthread_mutex_t g_stderr_capture_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_saved_stderr_fd = -1;
static int g_capture_read_fd = -1;

/**
 * Pins the target thread [tid] (0 = current thread) to the intersection of [cpuIds] and the
 * CPUs currently permitted by the kernel/cgroup cpuset. Intersecting with the live allowed mask
 * guarantees compatibility across all Android OEMs, hotplugged cores, and cgroup transitions
 * without ever failing with EINVAL or pinning onto an offline core.
 *
 * Returns 0 on success, or negative errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_example_service_NativeCpuAffinity_nativeSetThreadAffinity(
        JNIEnv *env,
        jclass clazz,
        jint tid,
        jintArray cpuIds) {
    (void) clazz;
    if (cpuIds == NULL) {
        return -EINVAL;
    }

    jsize len = (*env)->GetArrayLength(env, cpuIds);
    if (len <= 0) {
        return -EINVAL;
    }

    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    int hasAllowed = 0;
    if (syscall(SYS_sched_getaffinity, (pid_t) tid, sizeof(cpu_set_t), &allowed) == 0 &&
        CPU_COUNT(&allowed) > 0) {
        hasAllowed = 1;
    } else if (tid != 0 &&
               syscall(SYS_sched_getaffinity, (pid_t) 0, sizeof(cpu_set_t), &allowed) == 0 &&
               CPU_COUNT(&allowed) > 0) {
        hasAllowed = 1;
    }

    jint *ids = (*env)->GetIntArrayElements(env, cpuIds, NULL);
    if (ids == NULL) {
        return -ENOMEM;
    }

    cpu_set_t desired;
    CPU_ZERO(&desired);
    for (jsize i = 0; i < len; i++) {
        jint cpu = ids[i];
        if (cpu >= 0 && cpu < CPU_SETSIZE) {
            if (!hasAllowed || CPU_ISSET(cpu, &allowed)) {
                CPU_SET(cpu, &desired);
            }
        }
    }
    (*env)->ReleaseIntArrayElements(env, cpuIds, ids, JNI_ABORT);

    if (CPU_COUNT(&desired) == 0) {
        return -EINVAL;
    }

    if (syscall(SYS_sched_setaffinity, (pid_t) tid, sizeof(cpu_set_t), &desired) != 0) {
        return -errno;
    }
    return 0;
}

/**
 * Restores a thread's affinity mask across all online/allowed CPUs [0..cpuCount-1].
 * Returns 0 on success, or negative errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_example_service_NativeCpuAffinity_nativeResetThreadAffinity(
        JNIEnv *env,
        jclass clazz,
        jint tid,
        jint cpuCount) {
    (void) env;
    (void) clazz;
    if (cpuCount <= 0) {
        return -EINVAL;
    }

    cpu_set_t all;
    CPU_ZERO(&all);
    int limit = cpuCount < CPU_SETSIZE ? cpuCount : CPU_SETSIZE;
    for (int cpu = 0; cpu < limit; cpu++) {
        CPU_SET(cpu, &all);
    }

    if (syscall(SYS_sched_setaffinity, (pid_t) tid, sizeof(cpu_set_t), &all) != 0) {
        return -errno;
    }
    return 0;
}

/**
 * Returns the CPU IDs currently allowed for this process by the Linux scheduler / Android cgroup.
 */
JNIEXPORT jintArray JNICALL
Java_com_example_service_NativeCpuAffinity_nativeGetProcessAllowedCpus(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (syscall(SYS_sched_getaffinity, (pid_t) 0, sizeof(cpu_set_t), &allowed) != 0) {
        return (*env)->NewIntArray(env, 0);
    }

    int count = CPU_COUNT(&allowed);
    if (count <= 0) {
        return (*env)->NewIntArray(env, 0);
    }

    jint buf[CPU_SETSIZE];
    int outIdx = 0;
    for (int cpu = 0; cpu < CPU_SETSIZE && outIdx < count; cpu++) {
        if (CPU_ISSET(cpu, &allowed)) {
            buf[outIdx++] = (jint) cpu;
        }
    }

    jintArray result = (*env)->NewIntArray(env, outIdx);
    if (result != NULL && outIdx > 0) {
        (*env)->SetIntArrayRegion(env, result, 0, outIdx, buf);
    }
    return result;
}

/**
 * Queries the Linux ELF auxiliary vector (`getauxval(AT_HWCAP / AT_HWCAP2)`) to report the exact
 * AArch64 SIMD extensions supported by the host CPU at runtime without executing risky probes.
 *
 * Bitmask layout (matches CpuSimdProfile in CpuTopology.kt):
 *  - bit 0 (1): NEON / ASIMD
 *  - bit 1 (2): DotProd (ARMv8.2+ ASIMDDP)
 *  - bit 2 (4): I8MM (ARMv8.6+ Int8 Matrix Multiply)
 *  - bit 3 (8): SVE
 *  - bit 4 (16): SVE2
 */
JNIEXPORT jint JNICALL
Java_com_example_service_NativeCpuAffinity_nativeGetArm64SimdMask(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
#if defined(__aarch64__)
    unsigned long hwcap = getauxval(AT_HWCAP);
    unsigned long hwcap2 = getauxval(AT_HWCAP2);
    jint mask = 0;
    if ((hwcap & HWCAP_ASIMD) != 0UL) {
        mask |= (1 << 0);
    }
    if ((hwcap & HWCAP_ASIMDDP) != 0UL) {
        mask |= (1 << 1);
    }
    if ((hwcap2 & HWCAP2_I8MM) != 0UL) {
        mask |= (1 << 2);
    }
    if ((hwcap & HWCAP_SVE) != 0UL) {
        mask |= (1 << 3);
    }
    if ((hwcap2 & HWCAP2_SVE2) != 0UL) {
        mask |= (1 << 4);
    }
    return mask;
#else
    return 0;
#endif
}

/**
 * Temporarily redirects POSIX `stderr` (`fd 2`) into a non-blocking pipe so short one-shot
 * native probes (such as `libx265`, which writes `x265 [info]: using cpu capabilities: ...`
 * directly via `fprintf(stderr)` instead of FFmpeg's `av_log`) can be captured safely without
 * ever blocking. Must always be paired with `nativeEndStderrCapture`.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_service_NativeCpuAffinity_nativeBeginStderrCapture(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_stderr_capture_lock);
    if (g_saved_stderr_fd >= 0 || g_capture_read_fd >= 0) {
        pthread_mutex_unlock(&g_stderr_capture_lock);
        return JNI_FALSE;
    }

    int pipeFds[2] = {-1, -1};
    if (pipe(pipeFds) != 0) {
        pthread_mutex_unlock(&g_stderr_capture_lock);
        return JNI_FALSE;
    }

    int rFlags = fcntl(pipeFds[0], F_GETFL, 0);
    if (rFlags >= 0) {
        fcntl(pipeFds[0], F_SETFL, rFlags | O_NONBLOCK);
    }
    int wFlags = fcntl(pipeFds[1], F_GETFL, 0);
    if (wFlags >= 0) {
        fcntl(pipeFds[1], F_SETFL, wFlags | O_NONBLOCK);
    }

    fflush(stderr);
    int saved = dup(STDERR_FILENO);
    if (saved < 0) {
        close(pipeFds[0]);
        close(pipeFds[1]);
        pthread_mutex_unlock(&g_stderr_capture_lock);
        return JNI_FALSE;
    }

    if (dup2(pipeFds[1], STDERR_FILENO) < 0) {
        close(saved);
        close(pipeFds[0]);
        close(pipeFds[1]);
        pthread_mutex_unlock(&g_stderr_capture_lock);
        return JNI_FALSE;
    }
    close(pipeFds[1]);

    g_saved_stderr_fd = saved;
    g_capture_read_fd = pipeFds[0];
    pthread_mutex_unlock(&g_stderr_capture_lock);
    return JNI_TRUE;
}

/**
 * Restores POSIX `stderr` (`fd 2`), drains up to 16 KB captured from the non-blocking pipe,
 * and returns it as a Java String.
 */
JNIEXPORT jstring JNICALL
Java_com_example_service_NativeCpuAffinity_nativeEndStderrCapture(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;
    pthread_mutex_lock(&g_stderr_capture_lock);
    int saved = g_saved_stderr_fd;
    int readFd = g_capture_read_fd;
    g_saved_stderr_fd = -1;
    g_capture_read_fd = -1;

    if (saved >= 0) {
        fflush(stderr);
        dup2(saved, STDERR_FILENO);
        close(saved);
    }
    pthread_mutex_unlock(&g_stderr_capture_lock);

    char buffer[16384];
    size_t total = 0;
    if (readFd >= 0) {
        while (total < sizeof(buffer) - 1) {
            ssize_t n = read(readFd, buffer + total, (sizeof(buffer) - 1) - total);
            if (n <= 0) {
                break;
            }
            total += (size_t) n;
        }
        close(readFd);
    }
    buffer[total] = '\0';
    // Strip any embedded NUL bytes to keep NewStringUTF valid.
    for (size_t i = 0; i < total; i++) {
        if (buffer[i] == '\0') {
            buffer[i] = ' ';
        }
    }
    return (*env)->NewStringUTF(env, buffer);
}

/**
 * Tunes Android Bionic's Scudo/jemalloc page-decay policy during heavy software video decode/encode.
 *
 * Android's Zygote configures app processes with `M_DECAY_TIME = 0` (immediate `madvise(MADV_DONTNEED)`
 * on freed blocks), whereas standalone CLI tools like Termux keep lazy page decay. During 1080p/4K
 * decoding and encoding, FFmpeg constantly frees and reallocates multi-megabyte AVFrame buffers;
 * immediate purging causes thousands of extra `madvise`/page-fault kernel transitions (`stime`).
 *
 * - `encodingActive == JNI_TRUE`: enables lazy page decay (`M_DECAY_TIME = 1`) so hot frame buffers
 *   are reused without kernel page-fault storms.
 * - `encodingActive == JNI_FALSE`: restores immediate decay (`M_DECAY_TIME = 0`) and purges cached
 *   pages (`M_PURGE`) so native memory is immediately returned to Android OS after the job ends.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_service_NativeCpuAffinity_nativeSetAllocatorEncodingMode(
        JNIEnv *env,
        jclass clazz,
        jboolean encodingActive) {
    (void) env;
    (void) clazz;
#if defined(__ANDROID__)
    if (encodingActive == JNI_TRUE) {
        int ok = mallopt(M_DECAY_TIME, 1);
        return ok == 1 ? JNI_TRUE : JNI_FALSE;
    } else {
        int okDecay = mallopt(M_DECAY_TIME, 0);
        int okPurge = mallopt(M_PURGE, 0);
        return (okDecay == 1 || okPurge == 1) ? JNI_TRUE : JNI_FALSE;
    }
#else
    (void) encodingActive;
    return JNI_FALSE;
#endif
}
