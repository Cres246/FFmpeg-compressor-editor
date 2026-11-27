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
 * Key design decision (Termux parity):
 * - Detects Prime/Big/Little clusters and max clock frequency from sysfs for hardware telemetry.
 * - Intentionally keeps ALL cores unlocked (`-threads 0` for `libx264`, `pools=+` for `libx265`,
 *   and NO `sched_setaffinity` Little-core exclusion). Real-world benchmarks against native Termux
 *   encoding showed that excluding Little cores and capping `-threads`/`pools=` to `perfCount`
 *   leaves 2–4 Efficiency cores idle and oversubscribes Big cores, causing ~20–30% lower
 *   throughput and earlier thermal throttling compared to letting Linux EAS + x264/x265 utilize
 *   every core.
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
     * Disabled: hard-pinning `x264`/`x265` worker pools onto Prime+Big cores leaves Little cores
     * idle and increases Big-core contention/thermals versus Termux's all-core EAS scheduling.
     */
    val shouldPinEncoderPoolToPerformanceCores: Boolean
        get() = false

    /**
     * Disabled: keep all worker threads unpinned so the Linux EAS scheduler can balance load
     * across Prime, Big, and Little cores without barrier stalls.
     */
    val shouldPinLeadThreadOnly: Boolean
        get() = false

    fun computeEncoderThreadProfile(): EncoderThreadProfile = EncoderThreadProfile(
        x264EncoderThreads = 0,
        x265PoolsArg = "+",
        x265FrameThreads = null,
        x265ExplicitWpp = false
    )

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

/**
 * Runtime ARM64 SIMD extension profile (`NEON`, `DotProd`, `I8MM`, `SVE`, `SVE2`).
 *
 * Queried primarily from the Linux ELF auxiliary vector (`getauxval(AT_HWCAP / AT_HWCAP2)`)
 * via [com.example.service.NativeCpuAffinity.getArm64SimdMask], with a pure `/proc/cpuinfo`
 * `Features` fallback so both live devices and JVM unit tests resolve cleanly.
 */
data class CpuSimdProfile(
    val hasNeon: Boolean,
    val hasDotProd: Boolean,
    val hasI8mm: Boolean,
    val hasSve: Boolean,
    val hasSve2: Boolean
) {
    fun summaryLabel(): String {
        val parts = buildList {
            if (hasNeon) add("NEON")
            if (hasDotProd) add("DotProd")
            if (hasI8mm) add("I8MM")
            if (hasSve2) {
                add("SVE2")
            } else if (hasSve) {
                add("SVE")
            }
        }
        return if (parts.isEmpty()) "ARM64" else parts.joinToString(" · ")
    }

    companion object {
        const val BIT_NEON = 1 shl 0
        const val BIT_DOTPROD = 1 shl 1
        const val BIT_I8MM = 1 shl 2
        const val BIT_SVE = 1 shl 3
        const val BIT_SVE2 = 1 shl 4

        @Volatile
        private var cached: CpuSimdProfile? = null

        fun fromBitmask(mask: Int): CpuSimdProfile = CpuSimdProfile(
            hasNeon = (mask and BIT_NEON) != 0,
            hasDotProd = (mask and BIT_DOTPROD) != 0,
            hasI8mm = (mask and BIT_I8MM) != 0,
            hasSve = (mask and BIT_SVE) != 0,
            hasSve2 = (mask and BIT_SVE2) != 0
        )

        fun parseCpuInfoFeatures(cpuInfoText: String): CpuSimdProfile {
            val tokens = hashSetOf<String>()
            cpuInfoText.lineSequence().forEach { line ->
                if (line.trimStart().startsWith("Features", ignoreCase = true)) {
                    val value = line.substringAfter(':', "")
                    value.split(' ', '\t').forEach { token ->
                        val cleaned = token.trim().lowercase()
                        if (cleaned.isNotEmpty()) tokens.add(cleaned)
                    }
                }
            }
            return CpuSimdProfile(
                hasNeon = "asimd" in tokens || "neon" in tokens,
                hasDotProd = "asimddp" in tokens || "dotprod" in tokens,
                hasI8mm = "i8mm" in tokens,
                hasSve = "sve" in tokens,
                hasSve2 = "sve2" in tokens
            )
        }

        fun detect(nativeMaskProvider: () -> Int): CpuSimdProfile {
            cached?.let { return it }
            val nativeMask = runCatching { nativeMaskProvider() }.getOrDefault(0)
            val resolved = if (nativeMask != 0) {
                fromBitmask(nativeMask)
            } else {
                val cpuInfo = runCatching {
                    val f = File("/proc/cpuinfo")
                    if (f.canRead()) f.readText() else ""
                }.getOrDefault("")
                parseCpuInfoFeatures(cpuInfo)
            }
            cached = resolved
            return resolved
        }
    }
}

/**
 * Actual runtime SIMD capabilities reported directly by bundled `libx264` and `libx265`
 * (`using cpu capabilities: ...`) during a lightweight 1-frame null probe, proving which
 * SIMD extensions (NEON, DotProd, I8MM) are actively executing in the compiled encoders.
 */
data class ActiveEncoderSimdReport(
    val x264Caps: String? = null,
    val x265Caps: String? = null
) {
    fun formatCompactLine(): String? {
        val parts = buildList {
            x264Caps?.takeIf { it.isNotBlank() }?.let { add("x264: $it") }
            x265Caps?.takeIf { it.isNotBlank() }?.let { add("x265: $it") }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    companion object {
        private val CPU_CAPS_REGEX = Regex(
            pattern = """using\s+cpu\s+capabilities\s*:\s*([^\r\n]+)""",
            option = RegexOption.IGNORE_CASE
        )

        fun parseEncoderCapabilities(logs: String): String? {
            if (logs.isBlank()) return null
            val match = CPU_CAPS_REGEX.find(logs) ?: return null
            return match.groupValues.getOrNull(1)
                ?.trim()
                ?.replace(Regex("""\s+"""), " ")
                ?.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Zero-polling, one-shot resolver for active runtime performance optimizations on a given
 * FFmpeg job. Computes the exact immutable stamp list ONCE at job startup (never polled or
 * re-checked while encoding runs) and only includes features that are genuinely active for
 * that specific workload.
 */
object ActivePerformanceStamps {

    enum class SoftwareWorkloadKind {
        VIDEO_X264,
        VIDEO_X265,
        VIDEO_OTHER_TRANSCODE,
        AUDIO_TRANSCODE,
        STREAM_COPY,
        PROBE_ONLY
    }

    fun classifyStandardWorkload(
        media: MediaInfo,
        config: ModeConfigState
    ): SoftwareWorkloadKind = when (config.mode) {
        ProcessingMode.CUSTOM_SIZE,
        ProcessingMode.DISCORD_20MB,
        ProcessingMode.WHATSAPP_100MB,
        ProcessingMode.GMAIL_25MB,
        ProcessingMode.VIDEO_ONLY_CRF,
        ProcessingMode.VIDEO_AND_AUDIO_CRF -> {
            if (config.codec == VideoCodecOption.H265) {
                SoftwareWorkloadKind.VIDEO_X265
            } else {
                SoftwareWorkloadKind.VIDEO_X264
            }
        }
        ProcessingMode.TRIM_VIDEO -> when (config.trimSubMode) {
            TrimSubMode.FULL_LOSSLESS -> SoftwareWorkloadKind.STREAM_COPY
            TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC -> {
                if (media.hasAudio) SoftwareWorkloadKind.AUDIO_TRANSCODE else SoftwareWorkloadKind.STREAM_COPY
            }
            TrimSubMode.CRF_AUDIO_COPY,
            TrimSubMode.CRF_AUDIO_AAC -> {
                if (config.codec == VideoCodecOption.H265) {
                    SoftwareWorkloadKind.VIDEO_X265
                } else {
                    SoftwareWorkloadKind.VIDEO_X264
                }
            }
        }
        ProcessingMode.REMUX_FORMAT -> SoftwareWorkloadKind.STREAM_COPY
        ProcessingMode.AUDIO_ONLY_AAC -> SoftwareWorkloadKind.AUDIO_TRANSCODE
        ProcessingMode.EXTRACT_AUDIO -> when (config.audioExtractFormat) {
            AudioExtractFormat.ORIGINAL -> SoftwareWorkloadKind.STREAM_COPY
            AudioExtractFormat.AAC -> {
                if (media.audioCodec?.equals("aac", ignoreCase = true) == true) {
                    SoftwareWorkloadKind.STREAM_COPY
                } else {
                    SoftwareWorkloadKind.AUDIO_TRANSCODE
                }
            }
            AudioExtractFormat.MP3 -> {
                if (media.audioCodec?.equals("mp3", ignoreCase = true) == true) {
                    SoftwareWorkloadKind.STREAM_COPY
                } else {
                    SoftwareWorkloadKind.AUDIO_TRANSCODE
                }
            }
        }
        ProcessingMode.CUSTOM_COMMAND -> SoftwareWorkloadKind.VIDEO_OTHER_TRANSCODE
    }

    fun classifyCustomCommandWorkload(steps: List<CustomCommandStep>): SoftwareWorkloadKind {
        val ffmpegSteps = steps.filter { it.tool == CustomCommandTool.FFMPEG }
        if (ffmpegSteps.isEmpty()) return SoftwareWorkloadKind.PROBE_ONLY

        var usesX265 = false
        var usesX264 = false
        var anyNonCopyVideo = false
        var anyAudioEncode = false

        for (step in ffmpegSteps) {
            val args = step.args
            var vCodec: String? = null
            var aCodec: String? = null
            var allCodec: String? = null
            var hasVn = false
            var hasAn = false
            var hasVideoFilter = false

            var i = 0
            while (i < args.size) {
                val token = args[i].lowercase()
                val next = args.getOrNull(i + 1)?.lowercase()
                when (token) {
                    "-c", "-codec" -> {
                        allCodec = next
                        i += 2
                        continue
                    }
                    "-c:v", "-vcodec", "-codec:v" -> {
                        vCodec = next
                        i += 2
                        continue
                    }
                    "-c:a", "-acodec", "-codec:a" -> {
                        aCodec = next
                        i += 2
                        continue
                    }
                    "-vn" -> hasVn = true
                    "-an" -> hasAn = true
                    "-vf", "-filter:v", "-filter_complex" -> hasVideoFilter = true
                }
                i++
            }

            val effV = vCodec ?: allCodec
            val effA = aCodec ?: allCodec
            when {
                effV == "libx265" || effV == "hevc" -> usesX265 = true
                effV == "libx264" || effV == "h264" -> usesX264 = true
                !hasVn && (effV != "copy" || hasVideoFilter) -> anyNonCopyVideo = true
            }
            if (!hasAn && effA != null && effA != "copy") {
                anyAudioEncode = true
            }
        }

        return when {
            usesX265 -> SoftwareWorkloadKind.VIDEO_X265
            usesX264 -> SoftwareWorkloadKind.VIDEO_X264
            anyNonCopyVideo -> SoftwareWorkloadKind.VIDEO_OTHER_TRANSCODE
            anyAudioEncode -> SoftwareWorkloadKind.AUDIO_TRANSCODE
            else -> SoftwareWorkloadKind.STREAM_COPY
        }
    }

    /**
     * Extracts clean canonical active SIMD extension names from raw `libx264`/`libx265`
     * `using cpu capabilities:` output (e.g., `"ARMv8 NEON DotProd I8MM"` or
     * `"NEON Neon_DotProd Neon_I8MM"` -> `["NEON", "DotProd", "I8MM"]`).
     */
    fun extractSimdTokens(rawCaps: String?): List<String> {
        if (rawCaps.isNullOrBlank()) return emptyList()
        val normalized = rawCaps.lowercase()
        val tokens = normalized.split(Regex("""[\s,;/]+""")).filter { it.isNotEmpty() }
        val hasNeon = tokens.any { it == "neon" || it == "asimd" || it.startsWith("neon_") }
        val hasDotProd = tokens.any { it == "dotprod" || it == "neon_dotprod" || it == "asimddp" }
        val hasI8mm = tokens.any { it == "i8mm" || it == "neon_i8mm" }
        val hasSve2 = tokens.any { it == "sve2" }
        val hasSve = tokens.any { it == "sve" }

        return buildList(4) {
            if (hasNeon) add("NEON")
            if (hasDotProd) add("DotProd")
            if (hasI8mm) add("I8MM")
            if (hasSve2) add("SVE2") else if (hasSve) add("SVE")
        }
    }

    fun resolve(
        workload: SoftwareWorkloadKind,
        customSimdBuild: Boolean,
        cpuSimd: CpuSimdProfile,
        encoderSimd: ActiveEncoderSimdReport?,
        scudoLazyDecayActive: Boolean,
        usesSafRead: Boolean
    ): List<String> = buildList(8) {
        when (workload) {
            SoftwareWorkloadKind.VIDEO_X264 -> {
                val verified = extractSimdTokens(encoderSimd?.x264Caps)
                if (verified.isNotEmpty()) {
                    addAll(verified)
                } else if (customSimdBuild) {
                    if (cpuSimd.hasNeon) add("NEON")
                    if (cpuSimd.hasDotProd) add("DotProd")
                    if (cpuSimd.hasI8mm) add("I8MM")
                }
            }
            SoftwareWorkloadKind.VIDEO_X265 -> {
                val verified = extractSimdTokens(encoderSimd?.x265Caps)
                if (verified.isNotEmpty()) {
                    addAll(verified)
                } else if (customSimdBuild) {
                    if (cpuSimd.hasNeon) add("NEON")
                    if (cpuSimd.hasDotProd) add("DotProd")
                    if (cpuSimd.hasI8mm) add("I8MM")
                }
            }
            SoftwareWorkloadKind.VIDEO_OTHER_TRANSCODE -> {
                if (cpuSimd.hasNeon) add("NEON")
                if (customSimdBuild && cpuSimd.hasDotProd) add("DotProd")
                if (customSimdBuild && cpuSimd.hasI8mm) add("I8MM")
            }
            SoftwareWorkloadKind.AUDIO_TRANSCODE -> {
                if (cpuSimd.hasNeon) add("NEON")
            }
            SoftwareWorkloadKind.STREAM_COPY,
            SoftwareWorkloadKind.PROBE_ONLY -> {
                // No codec SIMD active for pure stream copy / ffprobe metadata read
            }
        }

        if (customSimdBuild) {
            add("-O3")
            add("LTO-O3")
            add("Zero-PLT")
        }
        if (scudoLazyDecayActive && workload != SoftwareWorkloadKind.PROBE_ONLY) {
            add("Scudo")
        }
        if (customSimdBuild && usesSafRead) {
            add("256K SAF")
        }
    }

    /**
     * Formats a single concise one-shot terminal line with only the active items.
     * Returns `null` if nothing is active so the terminal stays completely clean.
     */
    fun formatTerminalLine(tag: String, stamps: List<String>): String? {
        if (stamps.isEmpty()) return null
        return "$tag ${stamps.joinToString(" · ")}"
    }
}


