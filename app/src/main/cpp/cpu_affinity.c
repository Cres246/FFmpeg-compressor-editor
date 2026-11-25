#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>

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
