package com.example.service

/**
 * Thin, crash-safe JNI bridge to Linux `sched_setaffinity` / `sched_getaffinity`.
 *
 * Gracefully degrades to a no-op (`isLoaded == false`) on JVM unit tests (Robolectric) or any
 * environment where the native library is unavailable.
 */
object NativeCpuAffinity {

    val isLoaded: Boolean = runCatching {
        System.loadLibrary("cpu_affinity")
        true
    }.getOrDefault(false)

    /**
     * Pins thread [tid] (0 = current thread) to the intersection of [cpuIds] and the process's
     * currently permitted cgroup CPU set.
     */
    fun pinThreadToCpus(tid: Int, cpuIds: IntArray): Boolean {
        if (!isLoaded || cpuIds.isEmpty()) return false
        return runCatching {
            nativeSetThreadAffinity(tid, cpuIds) == 0
        }.getOrDefault(false)
    }

    /**
     * Restores thread [tid]'s affinity across all [totalCpus] cores.
     */
    fun resetThreadAffinity(tid: Int, totalCpus: Int): Boolean {
        if (!isLoaded || totalCpus <= 0) return false
        return runCatching {
            nativeResetThreadAffinity(tid, totalCpus) == 0
        }.getOrDefault(false)
    }

    /**
     * Returns the CPU IDs currently allowed for this process by the Android kernel / cgroup.
     */
    fun getAllowedCpus(): IntArray {
        if (!isLoaded) return IntArray(0)
        return runCatching {
            nativeGetProcessAllowedCpus()
        }.getOrDefault(IntArray(0))
    }

    /**
     * Returns the AArch64 SIMD capability bitmask probed via `getauxval(AT_HWCAP / AT_HWCAP2)`,
     * or 0 on non-ARM64 / JVM test environments.
     */
    fun getArm64SimdMask(): Int {
        if (!isLoaded) return 0
        return runCatching {
            nativeGetArm64SimdMask()
        }.getOrDefault(0)
    }

    /**
     * Temporarily redirects native `stderr` into a non-blocking pipe to capture `libx265`
     * `using cpu capabilities:` output during the one-shot 1-frame SIMD probe.
     */
    fun beginStderrCapture(): Boolean {
        if (!isLoaded) return false
        return runCatching { nativeBeginStderrCapture() }.getOrDefault(false)
    }

    /**
     * Restores native `stderr` and returns captured text (empty string if unavailable).
     */
    fun endStderrCapture(): String {
        if (!isLoaded) return ""
        return runCatching { nativeEndStderrCapture().orEmpty() }.getOrDefault("")
    }

    /**
     * Configures Bionic/Scudo page decay for heavy native video frame churn:
     * - `active = true`: switches from Zygote's immediate `M_DECAY_TIME = 0` purge to lazy decay
     *   (`M_DECAY_TIME = 1`), preventing `madvise`/page-fault kernel `stime` storms on 1080p/4K frames.
     * - `active = false`: restores `M_DECAY_TIME = 0` and triggers `M_PURGE` to release native RAM.
     */
    fun setAllocatorEncodingMode(active: Boolean): Boolean {
        if (!isLoaded) return false
        return runCatching { nativeSetAllocatorEncodingMode(active) }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeSetThreadAffinity(tid: Int, cpuIds: IntArray): Int

    @JvmStatic
    private external fun nativeResetThreadAffinity(tid: Int, cpuCount: Int): Int

    @JvmStatic
    private external fun nativeGetProcessAllowedCpus(): IntArray

    @JvmStatic
    private external fun nativeGetArm64SimdMask(): Int

    @JvmStatic
    private external fun nativeBeginStderrCapture(): Boolean

    @JvmStatic
    private external fun nativeEndStderrCapture(): String?

    @JvmStatic
    private external fun nativeSetAllocatorEncodingMode(encodingActive: Boolean): Boolean
}
