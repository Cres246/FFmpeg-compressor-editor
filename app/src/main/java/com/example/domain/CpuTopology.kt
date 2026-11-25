package com.example.domain

import java.io.File

/**
 * Per-core hardware telemetry probed from Linux `/sys/devices/system/cpu/cpu<i>/`.
 */
data class CpuCoreSpec(
    val cpuId: Int,
    val maxFreqKhz: Long?,
    val capacity: Int?
)

/**
 * Adaptive thread & pool configuration for `libx264` and `libx265` derived from the host phone's
 * real CPU topology.
 */
data class EncoderThreadProfile(
    /** Value for `-threads` on `libx264` (0 = FFmpeg auto, >0 = explicit core-matched count). */
    val x264EncoderThreads: Int,
    /** Value for `pools=` in `-x265-params` ("+" = auto all cores, numeric string = Prime+Big pool). */
    val x265PoolsArg: String,
    /** Optional `frame-threads=` for `-x265-params` when pinning to Prime+Big cores. */
    val x265FrameThreads: Int?,
    /** Explicit `wpp=1` for `-x265-params` when tuned for heterogeneous Prime+Big pools. */
    val x265ExplicitWpp: Boolean
) {
    /**
     * Builds the base `-x265-params` prefix (e.g. `log-level=warning:pools=6:frame-threads=3:wpp=1`
     * on a 1+5+2 Snapdragon 8 Gen 3, or `log-level=warning:pools=+` on symmetric / all-big-core CPUs).
     */
    fun buildX265BaseParams(): String = buildString {
        append("log-level=warning:pools=")
        append(x265PoolsArg)
        if (x265FrameThreads != null && x265FrameThreads > 0) {
            append(":frame-threads=")
            append(x265FrameThreads)
        }
        if (x265ExplicitWpp) {
            append(":wpp=1")
        }
    }
}

/**
 * Dynamic, device-agnostic CPU cluster topology for ANY Android phone (Snapdragon, MediaTek
 * Dimensity, Samsung Exynos, Google Tensor, Kirin, symmetric mid-rangers, and all-big-core SoCs).
 *
 * Key design goal:
 * - On heterogeneous big.LITTLE / Prime+Big+Little phones with >= 4 performance cores
 *   (e.g. 1+5+2, 1+4+3, 1+3+4, 1+2+3+4, 1+4+4), excludes slow Efficiency (Little) cores from
 *   heavy `libx264`/`libx265` worker thread pools so frame/WPP sync barriers never stall waiting
 *   for slow cores (while leaving Little cores free for OS/UI/I/O).
 * - On all-big-core flagships (e.g. Snapdragon 8 Elite 2+6 Oryon, Dimensity 9300/9400), detects
 *   that all cores are high-capacity performance cores and keeps ALL cores unlocked.
 * - On 2-Big + 6-Little mid-rangers (perfCores < 4), does NOT starve the pool to 2 cores; pins
 *   only the single heaviest lookahead/control thread to the 2 Big cores and lets workers span all cores.
 * - On symmetric CPUs or restricted sysfs ROMs, cleanly falls back to standard all-core auto threading.
 */
data class CpuTopology(
    val totalCores: Int,
    val performanceCpuIds: IntArray,
    val littleCpuIds: IntArray,
    val primeCpuIds: IntArray,
    val maxFreqKhz: Long?,
    val isHeterogeneous: Boolean,
    val allBigCore: Boolean
) {
    /**
     * True when we have a substantial Prime+Big cluster (>= 4 cores) AND slow Little cores exist
     * that should be kept out of `x264`/`x265` synchronization barriers.
     */
    val shouldPinEncoderPoolToPerformanceCores: Boolean
        get() = isHeterogeneous &&
            !allBigCore &&
            littleCpuIds.isNotEmpty() &&
            performanceCpuIds.size >= MIN_PERF_CORES_FOR_POOL_PINNING &&
            performanceCpuIds.size < totalCores

    /**
     * True on 2-Big + 6-Little or 3-Big + N-Little phones where restricting the whole pool to
     * 2–3 cores would hurt throughput, so we only pin the top 1–2 lookahead/control threads to
     * Big cores while letting the full worker pool span all cores.
     */
    val shouldPinLeadThreadOnly: Boolean
        get() = isHeterogeneous &&
            !allBigCore &&
            littleCpuIds.isNotEmpty() &&
            performanceCpuIds.size in 1 until MIN_PERF_CORES_FOR_POOL_PINNING

    fun computeEncoderThreadProfile(): EncoderThreadProfile {
        if (!shouldPinEncoderPoolToPerformanceCores) {
            return EncoderThreadProfile(
                x264EncoderThreads = 0,
                x265PoolsArg = "+",
                x265FrameThreads = null,
                x265ExplicitWpp = false
            )
        }
        val perfCount = performanceCpuIds.size
        // x264 benefits from perfCount + 1 threads (hiding decode/filter bubbles across pinned Big cores
        // without causing heavy lock contention or spilling onto Little cores).
        val x264Threads = (perfCount + 1).coerceIn(4, totalCores)
        val x265FrameThreads = when {
            perfCount >= 6 -> 3
            perfCount >= 4 -> 2
            else -> 1
        }
        return EncoderThreadProfile(
            x264EncoderThreads = x264Threads,
            x265PoolsArg = perfCount.toString(),
            x265FrameThreads = x265FrameThreads,
            x265ExplicitWpp = true
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CpuTopology) return false
        return totalCores == other.totalCores &&
            performanceCpuIds.contentEquals(other.performanceCpuIds) &&
            littleCpuIds.contentEquals(other.littleCpuIds) &&
            primeCpuIds.contentEquals(other.primeCpuIds) &&
            maxFreqKhz == other.maxFreqKhz &&
            isHeterogeneous == other.isHeterogeneous &&
            allBigCore == other.allBigCore
    }

    override fun hashCode(): Int {
        var result = totalCores
        result = 31 * result + performanceCpuIds.contentHashCode()
        result = 31 * result + littleCpuIds.contentHashCode()
        result = 31 * result + primeCpuIds.contentHashCode()
        result = 31 * result + (maxFreqKhz?.hashCode() ?: 0)
        result = 31 * result + isHeterogeneous.hashCode()
        result = 31 * result + allBigCore.hashCode()
        return result
    }

    companion object {
        private const val MIN_PERF_CORES_FOR_POOL_PINNING = 4
        private const val LITTLE_CAPACITY_RATIO_THRESHOLD = 0.62
        private const val LITTLE_FREQ_RATIO_THRESHOLD = 0.78
        private const val ALL_BIG_MIN_FREQ_KHZ = 2_600_000L // >= 2.60 GHz is never a Little A55/A510/A520 core
        private const val PRIME_SCORE_RATIO_THRESHOLD = 0.96

        @Volatile
        private var cached: CpuTopology? = null

        fun current(): CpuTopology {
            cached?.let { return it }
            val detected = detectFromSysfs()
            cached = detected
            return detected
        }

        /**
         * Pure classifier suitable for both live Android devices and deterministic JVM unit tests.
         */
        fun classify(cores: List<CpuCoreSpec>): CpuTopology {
            val sortedCores = cores.sortedBy { it.cpuId }
            val total = sortedCores.size.coerceAtLeast(1)
            val allIds = sortedCores.map { it.cpuId }.toIntArray()
            val maxFreq = sortedCores.mapNotNull { it.maxFreqKhz }.filter { it > 0L }.maxOrNull()

            if (total <= 2) {
                return CpuTopology(
                    totalCores = total,
                    performanceCpuIds = allIds,
                    littleCpuIds = IntArray(0),
                    primeCpuIds = allIds,
                    maxFreqKhz = maxFreq,
                    isHeterogeneous = false,
                    allBigCore = false
                )
            }

            // Prefer kernel ARM64 `cpu_capacity` (reflects true IPC * freq across microarchitectures)
            // when all cores report valid values; otherwise fall back cleanly to `cpuinfo_max_freq`.
            val capacities = sortedCores.mapNotNull { it.capacity?.takeIf { c -> c > 0 } }
            val useCapacity = capacities.size == total && capacities.distinct().size > 1

            if (useCapacity) {
                val maxCap = capacities.max().toDouble()
                val minCap = capacities.min().toDouble()
                val isHetero = (minCap / maxCap) < 0.92
                if (!isHetero) {
                    return CpuTopology(
                        totalCores = total,
                        performanceCpuIds = allIds,
                        littleCpuIds = IntArray(0),
                        primeCpuIds = allIds,
                        maxFreqKhz = maxFreq,
                        isHeterogeneous = false,
                        allBigCore = false
                    )
                }
                val littleIds = sortedCores
                    .filter { (it.capacity ?: 0).toDouble() < maxCap * LITTLE_CAPACITY_RATIO_THRESHOLD }
                    .map { it.cpuId }
                    .toIntArray()
                val perfIds = sortedCores
                    .filter { it.cpuId !in littleIds }
                    .map { it.cpuId }
                    .toIntArray()
                val primeIds = sortedCores
                    .filter { (it.capacity ?: 0).toDouble() >= maxCap * PRIME_SCORE_RATIO_THRESHOLD }
                    .map { it.cpuId }
                    .toIntArray()
                val allBig = littleIds.isEmpty()
                return CpuTopology(
                    totalCores = total,
                    performanceCpuIds = if (perfIds.isNotEmpty()) perfIds else allIds,
                    littleCpuIds = littleIds,
                    primeCpuIds = if (primeIds.isNotEmpty()) primeIds else allIds,
                    maxFreqKhz = maxFreq,
                    isHeterogeneous = true,
                    allBigCore = allBig
                )
            }

            // Frequency-based classification (works on virtually every Android 7–16 phone)
            val freqs = sortedCores.mapNotNull { it.maxFreqKhz?.takeIf { f -> f > 0L } }
            val useFreq = freqs.size == total && freqs.distinct().size > 1
            if (!useFreq) {
                return CpuTopology(
                    totalCores = total,
                    performanceCpuIds = allIds,
                    littleCpuIds = IntArray(0),
                    primeCpuIds = allIds,
                    maxFreqKhz = maxFreq,
                    isHeterogeneous = false,
                    allBigCore = false
                )
            }

            val maxF = freqs.max().toDouble()
            val minF = freqs.min().toDouble()
            val isHetero = (minF / maxF) < 0.92
            if (!isHetero) {
                return CpuTopology(
                    totalCores = total,
                    performanceCpuIds = allIds,
                    littleCpuIds = IntArray(0),
                    primeCpuIds = allIds,
                    maxFreqKhz = maxFreq,
                    isHeterogeneous = false,
                    allBigCore = false
                )
            }

            // A core is classified as slow Efficiency (Little) ONLY if its max clock is both
            // meaningfully below the top cluster (<78%) AND below 2.60 GHz (preventing all-big-core
            // flagships like 3.53GHz+4.32GHz Oryon from having 6 performance cores mislabeled).
            val littleIds = sortedCores
                .filter { core ->
                    val f = core.maxFreqKhz ?: 0L
                    f > 0L && f.toDouble() < maxF * LITTLE_FREQ_RATIO_THRESHOLD && f < ALL_BIG_MIN_FREQ_KHZ
                }
                .map { it.cpuId }
                .toIntArray()
            val perfIds = sortedCores
                .filter { it.cpuId !in littleIds }
                .map { it.cpuId }
                .toIntArray()
            val primeIds = sortedCores
                .filter { (it.maxFreqKhz ?: 0L).toDouble() >= maxF * PRIME_SCORE_RATIO_THRESHOLD }
                .map { it.cpuId }
                .toIntArray()
            val allBig = littleIds.isEmpty()

            return CpuTopology(
                totalCores = total,
                performanceCpuIds = if (perfIds.isNotEmpty()) perfIds else allIds,
                littleCpuIds = littleIds,
                primeCpuIds = if (primeIds.isNotEmpty()) primeIds else allIds,
                maxFreqKhz = maxFreq,
                isHeterogeneous = true,
                allBigCore = allBig
            )
        }

        private fun detectFromSysfs(): CpuTopology {
            val presentIds = parseCpuListFile("/sys/devices/system/cpu/present")
                .ifEmpty {
                    val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    (0 until count).toList()
                }
            val specs = presentIds.map { cpuId ->
                val maxFreq = readLongFile("/sys/devices/system/cpu/cpu$cpuId/cpufreq/cpuinfo_max_freq")
                    ?: readLongFile("/sys/devices/system/cpu/cpu$cpuId/cpufreq/scaling_max_freq")
                val cap = readLongFile("/sys/devices/system/cpu/cpu$cpuId/cpu_capacity")?.toInt()
                CpuCoreSpec(cpuId = cpuId, maxFreqKhz = maxFreq, capacity = cap)
            }
            return classify(specs)
        }

        internal fun parseCpuListString(raw: String): List<Int> {
            val result = mutableListOf<Int>()
            raw.trim().split(',').forEach { token ->
                val part = token.trim()
                if (part.isEmpty()) return@forEach
                val dash = part.indexOf('-')
                if (dash > 0) {
                    val start = part.substring(0, dash).trim().toIntOrNull()
                    val end = part.substring(dash + 1).trim().toIntOrNull()
                    if (start != null && end != null && end >= start && end - start < 256) {
                        for (i in start..end) result.add(i)
                    }
                } else {
                    part.toIntOrNull()?.let { result.add(it) }
                }
            }
            return result.distinct().sorted()
        }

        private fun parseCpuListFile(path: String): List<Int> = runCatching {
            val f = File(path)
            if (f.canRead()) parseCpuListString(f.readText()) else emptyList()
        }.getOrDefault(emptyList())

        private fun readLongFile(path: String): Long? = runCatching {
            val f = File(path)
            if (f.canRead()) f.readText().trim().toLongOrNull() else null
        }.getOrNull()
    }
}
