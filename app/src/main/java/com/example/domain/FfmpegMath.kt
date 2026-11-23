package com.example.domain

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure Kotlin port of all mathematical formulas, keyframe heuristics, aspect-ratio scale filters,
 * filename conventions, and FFmpeg argument builders from ~/bin/termux-file-editor.
 */
object FfmpegMath {

    /**
     * Exact 2-Pass ABR video bitrate calculation:
     * total_kb = targetMb * 8000 * 0.96
     * v = floor((total_kb / dur) - audioBitrateKbps), minimum 80 kbps.
     */
    fun calculateTwoPassVideoBitrateKbps(
        targetMb: Double,
        durationSec: Double,
        audioBitrateKbps: Int
    ): Int {
        val safeDur = if (durationSec <= 0.0) 1.0 else durationSec
        val totalKb = targetMb * 8000.0 * 0.96
        val v = floor((totalKb / safeDur) - audioBitrateKbps).toInt()
        return v.coerceAtLeast(80)
    }

    fun isBitrateWarningLow(videoBitrateKbps: Int): Boolean = videoBitrateKbps <= 120

    /**
     * Recommends 30 FPS and 720p when the video is long and calculated 2-pass video bitrate
     * drops low enough to degrade 60 FPS / 1080p+ sharpness.
     */
    fun shouldRecommend30Fps720p(
        videoBitrateKbps: Int,
        codec: VideoCodecOption,
        durationSec: Double
    ): Boolean {
        val longVideoThreshold = if (codec == VideoCodecOption.H265) 950 else 1400
        return (durationSec >= 60.0 && videoBitrateKbps < longVideoThreshold) || videoBitrateKbps < 650
    }

    /**
     * Exact 3-way aspect ratio protection + odd-pixel protection (EVEN_VF):
     * 1) Portrait (HEIGHT > WIDTH): scale=<target>:-2
     * 2) Cinema Ultrawide (WIDTH * 9 > HEIGHT * 16): scale=<L_MAX>:-2
     * 3) Standard 16:9 / 4:3 / 1:1: scale=-2:<target>
     * 4) Original resolution with odd width/height: scale=trunc(iw/2)*2:trunc(ih/2)*2
     */
    fun buildScaleFilter(
        width: Int?,
        height: Int?,
        resolution: ResolutionOption,
        needsEvenScale: Boolean
    ): String? {
        val target = resolution.targetShort
        val lMax = resolution.maxLong
        if (target == null || lMax == null) {
            return if (needsEvenScale) "scale=trunc(iw/2)*2:trunc(ih/2)*2" else null
        }
        return if (width != null && height != null && width > 0 && height > 0) {
            when {
                height > width -> "scale=$target:-2"
                width.toLong() * 9L > height.toLong() * 16L -> "scale=$lMax:-2"
                else -> "scale=-2:$target"
            }
        } else {
            "scale=-2:$target"
        }
    }

    /**
     * Normalizes audio bitrate input (strips k/K, clamps 32..maxKbps, falls back to default).
     */
    fun normalizeAudioBitrate(raw: String, defaultKbps: Int, maxKbps: Int = 768): Int {
        val cleaned = raw.trim().replace("k", "", ignoreCase = true)
        val parsed = cleaned.toIntOrNull()
        return if (parsed != null && parsed in 32..maxKbps) parsed else defaultKbps.coerceIn(32, maxKbps)
    }

    fun defaultAudioBitrateForTwoPass(targetMb: Double): Int =
        if (targetMb <= 3.0) 64 else 128

    /**
     * Parses target MB string accepting both comma and dot decimal separators.
     */
    fun parsePositiveDouble(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        return if (value > 0.0 && value.isFinite()) value else null
    }

    /**
     * Formats target MB cleanly for output filename (e.g. 20.0 -> "20", 8.5 -> "8.5").
     */
    fun formatMbTag(mb: Double): String {
        val asLong = mb.toLong()
        return if (mb == asLong.toDouble()) asLong.toString()
        else String.format(Locale.US, "%.2f", mb).trimEnd('0').trimEnd('.')
    }

    /**
     * Parses timestamp in "SS", "SS.ms", "MM:SS", "HH:MM:SS" formats.
     */
    fun parseTimestampToSeconds(raw: String): Double? {
        val cleaned = raw.trim().replace(" ", "").replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(":")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.takeIf { it >= 0.0 }
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                if (m < 0 || s < 0) null else m * 60.0 + s
            }
            3 -> {
                val h = parts[0].toDoubleOrNull() ?: return null
                val m = parts[1].toDoubleOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                if (h < 0 || m < 0 || s < 0) null else h * 3600.0 + m * 60.0 + s
            }
            else -> null
        }
    }

    /**
     * Exact formatting matching script's awk fmt(x): MM:SS.d or H:MM:SS.d
     */
    fun formatKeyframeTime(
        seconds: Double?,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): String {
        if (seconds == null || seconds < 0.0) return strings.noneLabel
        val t10 = (seconds * 10.0 + 0.5).toLong()
        val h = t10 / 36000
        val m = (t10 % 36000) / 600
        val s10 = t10 % 600
        val s = s10 / 10
        val dec = s10 % 10
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, dec)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", m, s, dec)
        }
    }

    /**
     * Formats raw seconds to FFmpeg timestamp string (%.3f).
     */
    fun formatRawSeconds(seconds: Double): String =
        String.format(Locale.US, "%.3f", seconds)

    /**
     * Formats slider position cleanly as MM:SS.d or H:MM:SS.d for the trim input fields.
     */
    fun formatSliderTimestamp(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val t10 = (safe * 10.0 + 0.5).toLong()
        val h = t10 / 36000
        val m = (t10 % 36000) / 600
        val s10 = t10 % 600
        val s = s10 / 10
        val dec = s10 % 10
        return if (h > 0) {
            if (dec == 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, dec)
        } else {
            if (dec == 0L) String.format(Locale.US, "%02d:%02d", m, s)
            else String.format(Locale.US, "%02d:%02d.%d", m, s, dec)
        }
    }

    /**
     * Exact port of show_kf_at() awk parser:
     * Reads compact ffprobe packet lines with flags=K, prefers pts_time else dts_time.
     * Start locks to (prev_kf + 0.005) — 5ms buffer preventing jump to prior GOP.
     * End locks to raw next_kf PTS.
     */
    fun parseKeyframePackets(
        ffprobeOutput: String,
        targetSec: Double,
        isStart: Boolean
    ): KeyframePointResult {
        var prevKf: Double? = null
        var nextKf: Double? = null
        val nearby = mutableListOf<Double>()
        var lastFmt = ""

        for (line in ffprobeOutput.lineSequence()) {
            if (!line.contains("flags=K")) continue
            var pts = ""
            var dts = ""
            for (token in line.split('|')) {
                when {
                    token.startsWith("pts_time=") -> pts = token.removePrefix("pts_time=").trim()
                    token.startsWith("dts_time=") -> dts = token.removePrefix("dts_time=").trim()
                }
            }
            val rawTime = if (pts.isNotEmpty() && pts != "N/A") pts else dts
            val value = rawTime.toDoubleOrNull() ?: continue

            if (value <= targetSec + 0.05) {
                prevKf = value
            }
            if (nextKf == null && value >= targetSec - 0.05) {
                nextKf = value
            }
            if (value >= targetSec - 12.0 && value <= targetSec + 12.0) {
                val fmt = formatKeyframeTime(value)
                if (fmt != lastFmt) {
                    nearby.add(value)
                    lastFmt = fmt
                }
            }
            if (value > targetSec + 14.0) break
        }

        val recommendedRaw = if (isStart) {
            prevKf?.let { it + 0.005 }
        } else {
            nextKf
        }
        val recommendedDisplay = if (isStart) {
            prevKf?.let { formatKeyframeTime(it) }
        } else {
            nextKf?.let { formatKeyframeTime(it) }
        }

        return KeyframePointResult(
            targetSec = targetSec,
            prevKf = prevKf,
            nextKf = nextKf,
            recommendedRawSec = recommendedRaw,
            recommendedDisplay = recommendedDisplay,
            nearbyKeyframes = nearby
        )
    }

    /**
     * Picks up to [maxCount] keyframes symmetrically centered around [targetSec] so dense 1s-GOP
     * gallery videos always display both earlier keyframes (e.g. 12, 13, 14) and later keyframes
     * (e.g. 16, 17, 18) instead of truncating only the first 12.
     */
    fun selectCenteredKeyframes(
        keyframes: List<Double>,
        targetSec: Double,
        maxCount: Int = 12
    ): List<Double> {
        if (keyframes.size <= maxCount) return keyframes
        val beforeOrAt = keyframes.filter { it <= targetSec + 0.05 }
        val after = keyframes.filter { it > targetSec + 0.05 }
        val half = maxCount / 2

        var takeBefore = minOf(beforeOrAt.size, half)
        var takeAfter = minOf(after.size, maxCount - takeBefore)
        val remaining = maxCount - (takeBefore + takeAfter)
        if (remaining > 0) {
            if (beforeOrAt.size > takeBefore) {
                takeBefore = minOf(beforeOrAt.size, takeBefore + remaining)
            } else if (after.size > takeAfter) {
                takeAfter = minOf(after.size, takeAfter + remaining)
            }
        }

        val pickedBefore = beforeOrAt.takeLast(takeBefore)
        val pickedAfter = after.take(takeAfter)
        return (pickedBefore + pickedAfter).sorted()
    }

    /**
     * Exact extension mapping for Mode 9 (Orijinal lossless audio extraction).
     */
    fun resolveOriginalAudioExtension(audioCodec: String?): String {
        val c = audioCodec?.lowercase(Locale.US) ?: return "mka"
        return when {
            c == "aac" -> "m4a"
            c == "opus" -> "opus"
            c == "mp3" -> "mp3"
            c == "vorbis" -> "ogg"
            c == "flac" -> "flac"
            c == "ac3" -> "ac3"
            c == "eac3" -> "eac3"
            c == "alac" -> "m4a"
            c.startsWith("pcm_") -> "wav"
            else -> "mka"
        }
    }

    fun resolveMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ac3" -> "audio/ac3"
            "eac3" -> "audio/eac3"
            "mka" -> "audio/x-matroska"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    fun isVideoFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "flv", "3gp")
    }

    fun formatSizeBytes(bytes: Long): String {
        if (bytes <= 0L) return "?"
        val mb = bytes / 1048576.0
        return when {
            mb >= 1024.0 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%d KB", (bytes / 1024L).coerceAtLeast(1L))
        }
    }

    fun formatDurationShort(
        durationSec: Double?,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): String {
        if (durationSec == null || durationSec <= 0.0) return "?"
        return String.format(Locale.US, "%.1f %s", durationSec, strings.secondsUnitShort)
    }

    fun formatElapsedTime(
        elapsedSec: Double,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): String {
        val safe = elapsedSec.coerceAtLeast(0.0)
        return if (safe < 60.0) {
            String.format(Locale.US, "%.1f %s", safe, strings.secondsUnitShort)
        } else {
            val totalSec = safe.toLong()
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /**
     * Exact live telemetry status line matching script's run_ff():
     * TR: Süre: 24.6 / 61.4 sn (%40) | 1.34x | 4820kb
     * EN: Time: 24.6 / 61.4 s (40%) | 1.34x | 4820kb
     */
    fun formatTelemetryLine(
        currentSec: Double,
        totalDurationSec: Double?,
        percent: Int?,
        speed: String,
        sizeKb: Long,
        includePercent: Boolean = false,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): String {
        val curStr = String.format(Locale.US, "%.1f", currentSec.coerceAtLeast(0.0))
        val sizePart = if (sizeKb > 0) " | ${sizeKb}kb" else ""
        val speedPart = speed.ifBlank { "..." }
        val unit = strings.secondsUnitShort
        return if (totalDurationSec != null && totalDurationSec > 0.0) {
            val durStr = String.format(Locale.US, "%.1f", totalDurationSec)
            val pctPart = if (includePercent && percent != null) " (${strings.formatPercent(percent)})" else ""
            "${strings.telemetryPrefix}: $curStr / $durStr $unit$pctPart | $speedPart$sizePart"
        } else {
            "${strings.telemetryPrefix}: $curStr $unit | $speedPart$sizePart"
        }
    }

    /**
     * Computes effective start/end strings and clip duration for Mode 4 (Trim).
     */
    fun resolveTrimRange(
        config: ModeConfigState,
        mediaDurationSec: Double?
    ): Triple<String?, String?, Double?> {
        val startRaw = config.startTimeInput.trim().replace(" ", "")
        val endRaw = config.endTimeInput.trim().replace(" ", "")

        val useKeyframes = config.trimCutStrategy == TrimCutStrategy.LOSSLESS_KEYFRAME &&
            config.trimTimeChoice == TrimTimeChoice.SNAP_KEYFRAMES

        val effectiveStart = when {
            startRaw.isEmpty() -> null
            useKeyframes && config.selectedStartKfSec != null ->
                formatRawSeconds(config.selectedStartKfSec + 0.005)
            useKeyframes && config.trimScan.startScan?.recommendedRawSec != null ->
                formatRawSeconds(config.trimScan.startScan.recommendedRawSec)
            else -> startRaw
        }
        val effectiveEnd = when {
            endRaw.isEmpty() -> null
            useKeyframes && config.selectedEndKfSec != null ->
                formatRawSeconds(config.selectedEndKfSec)
            useKeyframes && config.trimScan.endScan?.recommendedRawSec != null ->
                formatRawSeconds(config.trimScan.endScan.recommendedRawSec)
            else -> endRaw
        }

        val ss = if (effectiveStart == null) 0.0 else parseTimestampToSeconds(effectiveStart)
        val ee = if (effectiveEnd == null) mediaDurationSec else parseTimestampToSeconds(effectiveEnd)
        val clipDur = if (ss != null && ee != null && ee > ss) ee - ss else null
        return Triple(effectiveStart, effectiveEnd, clipDur)
    }

    /**
     * Computes the exact output filename following every naming rule of the script.
     */
    fun computeOutputFileName(
        media: MediaInfo,
        config: ModeConfigState,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): String {
        val base = media.baseName.ifBlank { "video" }
        val codecTag = config.codec.tag
        val bitTag = config.bitDepth.tag
        val crfVal = config.codec.crf
        val clipTag = strings.fileTagClip
        val silentTag = strings.fileTagSilent
        val origTag = strings.fileTagOriginal
        val audioTag = strings.fileTagAudio

        return when (config.mode) {
            ProcessingMode.DISCORD_20MB -> "${base}_${codecTag}${bitTag}_20MB.mp4"
            ProcessingMode.WHATSAPP_100MB -> "${base}_${codecTag}${bitTag}_100MB.mp4"
            ProcessingMode.GMAIL_25MB -> "${base}_${codecTag}${bitTag}_25MB.mp4"
            ProcessingMode.CUSTOM_SIZE -> {
                val mb = parsePositiveDouble(config.targetMbInput) ?: 20.0
                "${base}_${codecTag}${bitTag}_${formatMbTag(mb)}MB.mp4"
            }
            ProcessingMode.TRIM_VIDEO -> {
                when (config.trimSubMode) {
                    TrimSubMode.FULL_LOSSLESS -> "${base}_${clipTag}.mp4"
                    TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC -> {
                        if (!media.hasAudio) "${base}_${clipTag}_${silentTag}.mp4"
                        else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 192)
                            "${base}_${clipTag}_AAC_${ab}k.mp4"
                        }
                    }
                    TrimSubMode.CRF_AUDIO_COPY ->
                        "${base}_${clipTag}_${codecTag}${bitTag}_CRF${crfVal}.mp4"
                    TrimSubMode.CRF_AUDIO_AAC -> {
                        if (!media.hasAudio) "${base}_${clipTag}_${codecTag}${bitTag}_CRF${crfVal}_${silentTag}.mp4"
                        else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 192)
                            "${base}_${clipTag}_${codecTag}${bitTag}_CRF${crfVal}_AAC_${ab}k.mp4"
                        }
                    }
                }
            }
            ProcessingMode.REMUX_FORMAT -> "${base}_Remux.mp4"
            ProcessingMode.AUDIO_ONLY_AAC -> {
                val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                "${base}_AAC_${ab}k.mp4"
            }
            ProcessingMode.VIDEO_ONLY_CRF -> "${base}_${codecTag}${bitTag}_CRF${crfVal}.mp4"
            ProcessingMode.VIDEO_AND_AUDIO_CRF -> {
                if (!media.hasAudio) "${base}_${codecTag}${bitTag}_CRF${crfVal}_${silentTag}.mp4"
                else {
                    val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                    "${base}_${codecTag}${bitTag}_CRF${crfVal}_AAC_${ab}k.mp4"
                }
            }
            ProcessingMode.EXTRACT_AUDIO -> {
                when (config.audioExtractFormat) {
                    AudioExtractFormat.ORIGINAL -> {
                        val ext = resolveOriginalAudioExtension(media.audioCodec)
                        "${base}_${origTag}.$ext"
                    }
                    AudioExtractFormat.AAC -> {
                        if (media.audioCodec.equals("aac", ignoreCase = true)) {
                            "${base}_${audioTag}.m4a"
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                            "${base}_${audioTag}_${ab}k.m4a"
                        }
                    }
                    AudioExtractFormat.MP3 -> {
                        if (media.audioCodec.equals("mp3", ignoreCase = true)) {
                            "${base}_${audioTag}.mp3"
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 256, maxKbps = 320)
                            "${base}_${audioTag}_${ab}k.mp3"
                        }
                    }
                }
            }
            ProcessingMode.CUSTOM_COMMAND -> strings.customCommandOutputPlaceholder
        }
    }

    /**
     * Builds exact FFmpeg argument list(s) for modes 1–9.
     * `inputPlaceholder` can be either a preview string ("$IN") or a real SAF parameter / path.
     */
    fun buildFfmpegSteps(
        media: MediaInfo,
        config: ModeConfigState,
        inputTokenProvider: () -> String,
        outputPath: String,
        passLogPrefix: String,
        strings: AppStrings = AppLanguageController.currentStrings()
    ): List<FfmpegStepSpec> {
        val codec = config.codec
        val pixFmt = config.bitDepth.pixFmt
        val preset = config.preset.value

        fun addH265CrfArgs(args: MutableList<String>) {
            if (codec == VideoCodecOption.H265) {
                args.addAll(listOf("-tag:v", "hvc1", "-x265-params", "log-level=warning:pools=+"))
            }
        }

        return when (config.mode) {
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> {
                val targetMb = when (config.mode) {
                    ProcessingMode.DISCORD_20MB -> 20.0
                    ProcessingMode.WHATSAPP_100MB -> 100.0
                    ProcessingMode.GMAIL_25MB -> 25.0
                    else -> parsePositiveDouble(config.targetMbInput) ?: 20.0
                }
                val dur = media.durationSec ?: parsePositiveDouble(config.manualDurationInput) ?: 1.0
                val defAb = defaultAudioBitrateForTwoPass(targetMb)
                val abps = if (!media.hasAudio) 0 else normalizeAudioBitrate(config.audioBitrateInput, defAb)
                val vkbps = calculateTwoPassVideoBitrateKbps(targetMb, dur, abps)
                val vf = buildScaleFilter(media.width, media.height, config.resolution, media.needsEvenScale)

                val pass1 = mutableListOf("-y", "-threads", "0", "-i", inputTokenProvider())
                config.fps.ffmpegValue?.let { pass1.addAll(listOf("-r", it)) }
                vf?.let { pass1.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                pass1.addAll(
                    listOf(
                        "-sn", "-dn",
                        "-c:v", codec.encoder,
                        "-threads", "0",
                        "-preset", preset,
                        "-b:v", "${vkbps}k",
                        "-pix_fmt", pixFmt
                    )
                )
                if (codec == VideoCodecOption.H265) {
                    pass1.addAll(
                        listOf(
                            "-x265-params",
                            "log-level=warning:pools=+:pass=1:stats=$passLogPrefix.log"
                        )
                    )
                } else {
                    pass1.addAll(listOf("-pass", "1", "-passlogfile", passLogPrefix))
                }
                pass1.addAll(listOf("-an", "-f", "null", "/dev/null"))

                val pass2 = mutableListOf("-y", "-threads", "0", "-i", inputTokenProvider())
                config.fps.ffmpegValue?.let { pass2.addAll(listOf("-r", it)) }
                vf?.let { pass2.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                pass2.addAll(
                    listOf(
                        "-sn", "-dn",
                        "-c:v", codec.encoder,
                        "-threads", "0",
                        "-preset", preset,
                        "-b:v", "${vkbps}k",
                        "-pix_fmt", pixFmt
                    )
                )
                if (codec == VideoCodecOption.H265) {
                    pass2.addAll(
                        listOf(
                            "-tag:v", "hvc1",
                            "-x265-params",
                            "log-level=warning:pools=+:pass=2:stats=$passLogPrefix.log"
                        )
                    )
                } else {
                    pass2.addAll(listOf("-pass", "2", "-passlogfile", passLogPrefix))
                }
                if (abps == 0) {
                    pass2.add("-an")
                } else {
                    pass2.addAll(listOf("-c:a", "aac", "-b:a", "${abps}k"))
                }
                pass2.addAll(listOf("-movflags", "+faststart", outputPath))

                listOf(
                    FfmpegStepSpec(strings.stepPass1, pass1, dur),
                    FfmpegStepSpec(strings.stepPass2, pass2, dur)
                )
            }

            ProcessingMode.TRIM_VIDEO -> {
                val (startT, endT, clipDur) = resolveTrimRange(config, media.durationSec)
                val args = mutableListOf("-y")
                when (config.trimSubMode) {
                    TrimSubMode.FULL_LOSSLESS -> {
                        if (startT != null) args.addAll(listOf("-noaccurate_seek", "-ss", startT))
                        if (endT != null) args.addAll(listOf("-to", endT))
                        args.addAll(
                            listOf(
                                "-i", inputTokenProvider(),
                                "-sn", "-dn",
                                "-c", "copy",
                                "-avoid_negative_ts", "make_zero",
                                "-movflags", "+faststart",
                                outputPath
                            )
                        )
                        listOf(FfmpegStepSpec(strings.stepTrimLossless, args, clipDur))
                    }
                    TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC -> {
                        if (startT != null) args.addAll(listOf("-noaccurate_seek", "-ss", startT))
                        if (endT != null) args.addAll(listOf("-to", endT))
                        args.addAll(listOf("-i", inputTokenProvider(), "-sn", "-dn", "-c:v", "copy"))
                        if (!media.hasAudio) {
                            args.add("-an")
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 192)
                            args.addAll(listOf("-c:a", "aac", "-b:a", "${ab}k"))
                        }
                        args.addAll(
                            listOf(
                                "-avoid_negative_ts", "make_zero",
                                "-movflags", "+faststart",
                                outputPath
                            )
                        )
                        listOf(FfmpegStepSpec(strings.stepTrimVideoLosslessAudioAac, args, clipDur))
                    }
                    TrimSubMode.CRF_AUDIO_COPY -> {
                        if (startT != null) args.addAll(listOf("-ss", startT))
                        if (endT != null) args.addAll(listOf("-to", endT))
                        val vf = buildScaleFilter(media.width, media.height, config.resolution, media.needsEvenScale)
                        args.addAll(listOf("-threads", "0", "-i", inputTokenProvider()))
                        config.fps.ffmpegValue?.let { args.addAll(listOf("-r", it)) }
                        vf?.let { args.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                        args.addAll(
                            listOf(
                                "-sn", "-dn",
                                "-c:v", codec.encoder,
                                "-threads", "0",
                                "-preset", preset,
                                "-crf", codec.crf.toString(),
                                "-pix_fmt", pixFmt
                            )
                        )
                        addH265CrfArgs(args)
                        if (!media.hasAudio) args.add("-an") else args.addAll(listOf("-c:a", "copy"))
                        args.addAll(listOf("-movflags", "+faststart", outputPath))
                        listOf(
                            FfmpegStepSpec(
                                strings.stepTrimCrfAudioCopy(codec.tag, config.bitDepth.tag, codec.crf, preset),
                                args,
                                clipDur
                            )
                        )
                    }
                    TrimSubMode.CRF_AUDIO_AAC -> {
                        if (startT != null) args.addAll(listOf("-ss", startT))
                        if (endT != null) args.addAll(listOf("-to", endT))
                        val vf = buildScaleFilter(media.width, media.height, config.resolution, media.needsEvenScale)
                        args.addAll(listOf("-threads", "0", "-i", inputTokenProvider()))
                        config.fps.ffmpegValue?.let { args.addAll(listOf("-r", it)) }
                        vf?.let { args.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                        args.addAll(
                            listOf(
                                "-sn", "-dn",
                                "-c:v", codec.encoder,
                                "-threads", "0",
                                "-preset", preset,
                                "-crf", codec.crf.toString(),
                                "-pix_fmt", pixFmt
                            )
                        )
                        addH265CrfArgs(args)
                        if (!media.hasAudio) {
                            args.add("-an")
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 192)
                            args.addAll(listOf("-c:a", "aac", "-b:a", "${ab}k"))
                        }
                        args.addAll(listOf("-movflags", "+faststart", outputPath))
                        listOf(
                            FfmpegStepSpec(
                                strings.stepTrimCrfAudioAac(codec.tag, config.bitDepth.tag, codec.crf, preset),
                                args,
                                clipDur
                            )
                        )
                    }
                }
            }

            ProcessingMode.REMUX_FORMAT -> {
                val args = listOf(
                    "-y", "-i", inputTokenProvider(),
                    "-sn", "-dn", "-c", "copy",
                    "-movflags", "+faststart",
                    outputPath
                )
                listOf(FfmpegStepSpec(strings.stepRemux, args, media.durationSec))
            }

            ProcessingMode.AUDIO_ONLY_AAC -> {
                val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                val args = listOf(
                    "-y", "-i", inputTokenProvider(),
                    "-sn", "-dn",
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "${ab}k",
                    "-movflags", "+faststart",
                    outputPath
                )
                listOf(FfmpegStepSpec(strings.stepAudioOnly(ab), args, media.durationSec))
            }

            ProcessingMode.VIDEO_ONLY_CRF -> {
                val vf = buildScaleFilter(media.width, media.height, ResolutionOption.ORIGINAL, media.needsEvenScale)
                val args = mutableListOf("-y", "-threads", "0", "-i", inputTokenProvider())
                vf?.let { args.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                args.addAll(
                    listOf(
                        "-sn", "-dn",
                        "-c:v", codec.encoder,
                        "-threads", "0",
                        "-preset", preset,
                        "-crf", codec.crf.toString(),
                        "-pix_fmt", pixFmt
                    )
                )
                addH265CrfArgs(args)
                if (!media.hasAudio) args.add("-an") else args.addAll(listOf("-c:a", "copy"))
                args.addAll(listOf("-movflags", "+faststart", outputPath))
                listOf(
                    FfmpegStepSpec(
                        strings.stepVideoOnly(codec.tag, config.bitDepth.tag, codec.crf, preset),
                        args,
                        media.durationSec
                    )
                )
            }

            ProcessingMode.VIDEO_AND_AUDIO_CRF -> {
                val vf = buildScaleFilter(media.width, media.height, ResolutionOption.ORIGINAL, media.needsEvenScale)
                val args = mutableListOf("-y", "-threads", "0", "-i", inputTokenProvider())
                vf?.let { args.addAll(listOf("-filter_threads", "0", "-vf", it)) }
                args.addAll(
                    listOf(
                        "-sn", "-dn",
                        "-c:v", codec.encoder,
                        "-threads", "0",
                        "-preset", preset,
                        "-crf", codec.crf.toString(),
                        "-pix_fmt", pixFmt
                    )
                )
                addH265CrfArgs(args)
                if (!media.hasAudio) {
                    args.add("-an")
                } else {
                    val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                    args.addAll(listOf("-c:a", "aac", "-b:a", "${ab}k"))
                }
                args.addAll(listOf("-movflags", "+faststart", outputPath))
                listOf(
                    FfmpegStepSpec(
                        "${codec.tag}${config.bitDepth.tag} CRF ${codec.crf} ($preset) + AAC",
                        args,
                        media.durationSec
                    )
                )
            }

            ProcessingMode.EXTRACT_AUDIO -> {
                val args = mutableListOf("-y", "-i", inputTokenProvider(), "-vn", "-sn", "-dn")
                val label = when (config.audioExtractFormat) {
                    AudioExtractFormat.ORIGINAL -> {
                        val ext = resolveOriginalAudioExtension(media.audioCodec)
                        args.addAll(listOf("-c:a", "copy", outputPath))
                        strings.stepExtractOriginal(media.audioCodec ?: "?", ext)
                    }
                    AudioExtractFormat.AAC -> {
                        if (media.audioCodec.equals("aac", ignoreCase = true)) {
                            args.addAll(listOf("-c:a", "copy", outputPath))
                            strings.stepExtractAacCopy
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 256)
                            args.addAll(listOf("-c:a", "aac", "-b:a", "${ab}k", outputPath))
                            strings.stepExtractAacTranscode(ab)
                        }
                    }
                    AudioExtractFormat.MP3 -> {
                        if (media.audioCodec.equals("mp3", ignoreCase = true)) {
                            args.addAll(listOf("-c:a", "copy", outputPath))
                            strings.stepExtractMp3Copy
                        } else {
                            val ab = normalizeAudioBitrate(config.audioBitrateInput, 256, maxKbps = 320)
                            args.addAll(listOf("-c:a", "libmp3lame", "-b:a", "${ab}k", outputPath))
                            strings.stepExtractMp3Transcode(ab)
                        }
                    }
                }
                listOf(FfmpegStepSpec(label, args, media.durationSec))
            }

            ProcessingMode.CUSTOM_COMMAND -> emptyList()
        }
    }

    /**
     * Splits a user-pasted Option 10 command on `&&` (outside quotes) into individual commands,
     * tokenizing shell quotes cleanly.
     */
    fun parseCustomCommandChain(rawCommand: String): List<List<String>> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < rawCommand.length) {
            val ch = rawCommand[i]
            when {
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                ch == '&' && !inSingle && !inDouble && i + 1 < rawCommand.length && rawCommand[i + 1] == '&' -> {
                    segments.add(current.toString().trim())
                    current.clear()
                    i++
                }
                else -> current.append(ch)
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) segments.add(tail)

        return segments.mapNotNull { segment ->
            val tokens = tokenizeShellArgs(segment).toMutableList()
            if (tokens.isEmpty()) return@mapNotNull null
            if (tokens.first().equals("ffmpeg", ignoreCase = true)) {
                tokens.removeAt(0)
            }
            tokens.takeIf { it.isNotEmpty() }
        }
    }

    fun tokenizeShellArgs(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasContent = false
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                ch == '\\' && !inSingle && i + 1 < command.length -> {
                    current.append(command[i + 1])
                    hasContent = true
                    i++
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasContent = true
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasContent = true
                }
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasContent) {
                        result.add(current.toString())
                        current.clear()
                        hasContent = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasContent = true
                }
            }
            i++
        }
        if (hasContent) result.add(current.toString())
        return result
    }

    /**
     * Resolves placeholders ($IN, $FILENAME, input.<ext>) and relative paths in a custom command.
     */
    fun resolveCustomCommandArgs(
        tokens: List<String>,
        media: MediaInfo,
        workDirPath: String,
        inputTokenProvider: () -> String
    ): List<String> {
        val inputAlias = if (media.extension.isNotBlank()) "input.${media.extension}" else "input"
        val resolved = mutableListOf<String>()
        var idx = 0
        while (idx < tokens.size) {
            val tok = tokens[idx]
            val prev = tokens.getOrNull(idx - 1)
            val replaced = when {
                tok == "\$IN" || tok == "\${IN}" || tok == media.fileName || tok == inputAlias -> {
                    inputTokenProvider()
                }
                tok.startsWith("-x265-params") -> tok
                prev == "-x265-params" && tok.contains("stats=") && !tok.contains("stats=/") -> {
                    tok.replace("stats=", "stats=$workDirPath/")
                }
                prev == "-passlogfile" && !tok.startsWith("/") -> {
                    "$workDirPath/$tok"
                }
                // Last argument or non-flag relative filename (not /dev/null and not a flag value for non-file flags)
                idx == tokens.lastIndex && !tok.startsWith("-") && !tok.startsWith("/") && tok != "-" -> {
                    "$workDirPath/$tok"
                }
                else -> tok
            }
            resolved.add(replaced)
            idx++
        }
        return resolved
    }

    fun formatCommandPreview(steps: List<FfmpegStepSpec>): String {
        return steps.joinToString("\n&& ") { step ->
            "ffmpeg " + step.args.joinToString(" ") { arg ->
                if (arg.contains(' ')) "\"$arg\"" else arg
            }
        }
    }

    fun calculatePercent(currentSec: Double, totalDurationSec: Double?): Int? {
        if (totalDurationSec == null || totalDurationSec <= 0.0) return null
        val pct = ((currentSec / totalDurationSec) * 100.0).roundToInt()
        return pct.coerceIn(0, 100)
    }
}
