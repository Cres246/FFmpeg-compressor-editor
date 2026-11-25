package com.example.domain

import java.util.Locale

/**
 * Snapshot of decoder/encoder MIME types advertised by the current Android device's
 * [android.media.MediaCodecList] (REGULAR_CODECS). Passed into [Media3CompatibilityAdvisor]
 * so format compatibility decisions combine Jetpack Media3 1.11.1's verified container/muxer
 * support with the exact hardware/platform codecs installed on the user's phone.
 */
data class DeviceCodecSnapshot(
    val decoderMimes: Set<String> = emptySet(),
    val hardwareDecoderMimes: Set<String> = emptySet(),
    val encoderMimes: Set<String> = emptySet(),
    val supportsHevc10BitEncode: Boolean = true,
    val supportsHevcHdrEditing: Boolean = true,
    val sdkInt: Int = 36,
    val probed: Boolean = false
)

enum class Media3IssueKind {
    /** Container, video, and audio are compatible with Media3 1.11.1 + device MediaCodec. */
    COMPATIBLE,
    /** Video & audio codecs are supported; only container needs fast lossless MP4 remux. */
    CONTAINER_ONLY,
    /** Video is supported, but the audio codec/channel layout is unsupported by Media3/MediaCodec. */
    AUDIO_INCOMPATIBLE,
    /** Video codec, profile (e.g. 10-bit H.264), or pixel format is unsupported by Media3/MediaCodec. */
    VIDEO_INCOMPATIBLE,
    /** Both video and audio streams are incompatible; full Video+Audio re-encode is required. */
    VIDEO_AND_AUDIO_INCOMPATIBLE
}

data class Media3CompatibilityReport(
    val issueKind: Media3IssueKind,
    val requiresImmediateRecovery: Boolean,
    val containerName: String,
    val videoCodecName: String?,
    val audioCodecName: String?,
    val videoIssueTag: String? = null,
    val audioIssueTag: String? = null,
    val containerIssueTag: String? = null,
    val recommendedAudioBitrateKbps: Int = 256,
    val canUseVideoOnlyPrep: Boolean = false
)

/**
 * Embedded format knowledge base for **Jetpack Media3 1.11.1 Transformer** and Android platform
 * MediaCodec pipelines (verified directly against Media3 1.11.1 `DefaultExtractorsFactory`,
 * `Mp4Muxer.SUPPORTED_*_SAMPLE_MIME_TYPES`, `DefaultEncoderFactory`, and Android platform
 * supported media formats).
 *
 * Pinpoints upfront whether an incompatibility lives in the container, audio track, or video track
 * using the metadata already probed into [MediaInfo], and drives the progressive Turbo recovery
 * ladder: Remux -> Audio-Only (source-matched high quality AAC) -> Video-Only (when audio is
 * already MP4/Media3-compatible & sync-safe) -> Video+Audio -> Auto-Turbo.
 */
object Media3CompatibilityAdvisor {

    // Verified from Media3 1.11.1 DefaultExtractorsFactory & Transformer docs
    val NATIVE_MP4_CONTAINERS: Set<String> = setOf(
        "mp4", "m4v", "mov", "3gp", "3g2", "m4a", "fmp4", "mp4v"
    )

    // Media3 ExoPlayer extractors handle these progressively, though container remux may be needed
    // if timestamps/edit-lists are quirky or seeking is required.
    val EXTRACTABLE_NON_MP4_CONTAINERS: Set<String> = setOf(
        "mkv", "webm", "ts", "m2ts", "mts", "mpeg", "mpg", "ogg", "ogm", "wav", "flac", "mp3", "aac", "amr", "avi"
    )

    // Containers that Media3 1.11.1 cannot extract or cannot reliably seek/trim (FlvExtractor is
    // officially non-seekable per Android Media3 docs).
    val UNSUPPORTED_CONTAINERS: Set<String> = setOf(
        "wmv", "asf", "vob", "rm", "rmvb", "mxf", "ogv", "f4v", "divx", "m2v", "nut", "y4m", "wtv", "dv", "gxf"
    )

    // Legacy containers whose audio interleaving / VBR headers / missing PTS frequently drift when
    // copying audio (`-c:a copy`) into MP4 while re-encoding video; route those to Video+Audio.
    val LEGACY_SYNC_RISK_CONTAINERS: Set<String> = setOf(
        "avi", "wmv", "asf", "vob", "mpg", "mpeg", "rm", "rmvb", "dv", "mxf", "divx", "wtv", "gxf"
    )

    // Verified from Media3 1.11.1 Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES + Android decoders
    val VIDEO_CODEC_TO_MIME: Map<String, String> = mapOf(
        "h264" to "video/avc",
        "avc" to "video/avc",
        "avc1" to "video/avc",
        "hevc" to "video/hevc",
        "h265" to "video/hevc",
        "hvc1" to "video/hevc",
        "hev1" to "video/hevc",
        "vp9" to "video/x-vnd.on2.vp9",
        "vp8" to "video/x-vnd.on2.vp8",
        "av1" to "video/av01",
        "av01" to "video/av01",
        "mpeg4" to "video/mp4v-es",
        "mp4v" to "video/mp4v-es",
        "h263" to "video/3gpp",
        "apv" to "video/apv",
        "dolbyvision" to "video/dolby-vision",
        "dvhe" to "video/dolby-vision",
        "dvh1" to "video/dolby-vision"
    )

    // Video codecs known to lack Android MediaCodec platform decoders in Media3 Transformer
    val UNSUPPORTED_VIDEO_CODECS: Set<String> = setOf(
        "mpeg1video", "mpeg2video", "vc1", "wmv1", "wmv2", "wmv3",
        "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "prores", "dnxhd",
        "theora", "rv10", "rv20", "rv30", "rv40", "flv1", "h261",
        "mjpeg", "ffv1", "utvideo", "rawvideo", "qtrle", "cinepak",
        "svq1", "svq3", "vp6", "vp6f", "vp6a", "vp7", "indeo2",
        "indeo3", "indeo4", "indeo5", "binkvideo", "dirac", "gif"
    )

    // Verified from Media3 1.11.1 Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES + Android decoders
    // Note: Media3 Transformer does NOT bundle ExoPlayer's FFmpeg/software audio decoder modules.
    val GUARANTEED_PLATFORM_AUDIO_CODECS: Map<String, String> = mapOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "vorbis" to "audio/vorbis",
        "flac" to "audio/flac",
        "amr_nb" to "audio/3gpp",
        "amr_wb" to "audio/amr-wb",
        "pcm_s16le" to "audio/raw",
        "pcm_u8" to "audio/raw"
    )

    // Audio codecs only supported if the specific OEM/device exposes a platform MediaCodec decoder
    val DEVICE_OPTIONAL_AUDIO_CODECS: Map<String, Set<String>> = mapOf(
        "ac3" to setOf("audio/ac3"),
        "eac3" to setOf("audio/eac3", "audio/eac3-joc"),
        "ac4" to setOf("audio/ac4")
    )

    // Audio codecs never reliably decoded by standard Android MediaCodec in Media3 Transformer
    val UNSUPPORTED_AUDIO_CODECS: Set<String> = setOf(
        "dts", "dca", "truehd", "mlp", "alac",
        "wmav1", "wmav2", "wmapro", "wmalossless", "wmavoice",
        "mp1", "mp2", "cook", "ape", "tta", "wavpack", "tak",
        "speex", "gsm", "gsm_ms", "musepack7", "musepack8",
        "atrac1", "atrac3", "atrac3p", "ra_144", "ra_288",
        "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be",
        "pcm_f32le", "pcm_f32be", "pcm_f64le", "pcm_f64be",
        "pcm_alaw", "pcm_mulaw"
    )

    // Audio codecs that FFmpeg can cleanly copy into MP4 without re-encoding
    val MP4_COPY_SAFE_AUDIO_CODECS: Set<String> = setOf(
        "aac", "mp3", "ac3", "eac3", "opus", "alac", "flac"
    )

    // Subset of MP4-copyable audio codecs that are ALSO cleanly accepted inside MP4 by Media3 1.11.1
    // + Android MediaExtractor without needing audio re-encoding (excludes Vorbis/PCM/FLAC/ALAC).
    val VIDEO_ONLY_MP4_SAFE_AUDIO_CODECS: Set<String> = setOf(
        "aac", "mp3", "opus"
    )

    private val HIGH_QUALITY_AAC_LADDER = listOf(128, 192, 256, 320, 384, 512, 640, 768)

    /**
     * Picks a high-quality default AAC bitrate for Audio-Only Turbo preparation that faithfully
     * matches the source track without degrading it (e.g. 754k/768k 5.1 DTS/E-AC-3 -> 768k;
     * 640k AC-3 -> 640k; lossless/DTS multichannel unknown/low bitrate -> 384k; stereo -> 320k;
     * lossy 192k -> 192k; lossy 256k -> 256k; unknown -> 256k).
     */
    fun recommendHighQualityAudioBitrateKbps(media: MediaInfo): Int {
        if (!media.hasAudio) return 256
        val channels = (media.audioChannels ?: 2).coerceAtLeast(1)
        val channelCeiling = if (channels > 2) 768 else 320
        val codec = media.audioCodec?.lowercase(Locale.US).orEmpty()
        val srcKbps = media.audioBitrateKbps

        if (media.isLosslessAudio || codec in setOf("dts", "dca", "truehd", "mlp")) {
            if (channels > 2) {
                if (srcKbps != null && srcKbps >= 480) {
                    return (HIGH_QUALITY_AAC_LADDER.firstOrNull { it >= srcKbps - 12 } ?: 768)
                        .coerceIn(384, 768)
                }
                return 384
            }
            return 320
        }
        if (srcKbps != null && srcKbps > 0) {
            val matched = HIGH_QUALITY_AAC_LADDER.firstOrNull { it >= srcKbps - 12 }
                ?: HIGH_QUALITY_AAC_LADDER.last()
            return matched.coerceIn(128, channelCeiling)
        }
        return if (channels > 2) 320 else 256
    }

    /**
     * Quick chip choices offered in the Turbo Audio-Only recovery picker, mirroring
     * [FfmpegMath.resolveAudioBitrateQuickOptions] up to 768 kbps so high-bitrate 5.1 / DTS /
     * E-AC-3 / lossless tracks expose every faithful tier (up to 640k / 768k + exact source).
     */
    fun audioRecoveryBitrateChoices(
        sourceKbps: Int?,
        isLosslessSource: Boolean,
        recommendedKbps: Int,
        maxKbps: Int = 768
    ): List<Int> {
        val rawOptions = FfmpegMath.resolveAudioBitrateQuickOptions(
            sourceKbps = sourceKbps,
            isLosslessSource = isLosslessSource,
            maxKbps = maxKbps
        )
        val trimmed = if (sourceKbps == null || sourceKbps >= 128) {
            rawOptions.filter { it >= 128 }.ifEmpty { rawOptions }
        } else {
            rawOptions
        }
        return (trimmed + recommendedKbps.coerceIn(32, maxKbps)).distinct().sorted()
    }

    fun audioRecoveryBitrateChoices(media: MediaInfo): List<Int> =
        audioRecoveryBitrateChoices(
            sourceKbps = media.audioBitrateKbps,
            isLosslessSource = media.isLosslessAudio,
            recommendedKbps = recommendHighQualityAudioBitrateKbps(media),
            maxKbps = 768
        )

    /**
     * Diagnoses Media3 1.11.1 + device MediaCodec compatibility directly from [MediaInfo]
     * and the device's [DeviceCodecSnapshot].
     */
    fun diagnose(
        media: MediaInfo,
        config: ModeConfigState,
        device: DeviceCodecSnapshot = DeviceCodecSnapshot()
    ): Media3CompatibilityReport {
        val ext = media.extension.lowercase(Locale.US).trim()
        val vCodec = media.videoCodec?.lowercase(Locale.US)?.trim()
        val aCodec = media.audioCodec?.lowercase(Locale.US)?.trim()
        val pixFmt = media.pixFmt?.lowercase(Locale.US)?.trim().orEmpty()
        val profile = media.videoProfile?.lowercase(Locale.US)?.trim().orEmpty()
        val recommendedAudio = recommendHighQualityAudioBitrateKbps(media)

        // 1. Video stream diagnosis
        var videoIssue: String? = null
        if (!vCodec.isNullOrBlank()) {
            val videoMime = VIDEO_CODEC_TO_MIME[vCodec]
            when {
                vCodec in UNSUPPORTED_VIDEO_CODECS -> {
                    videoIssue = vCodec.uppercase(Locale.US)
                }
                // 10-bit / 12-bit H.264 (Hi10P) is NOT decoded by Android MediaCodec HW decoders
                (vCodec == "h264" || vCodec == "avc" || vCodec == "avc1") &&
                    (media.bitDepthIn == "10bit" || media.bitDepthIn == "12bit" || profile.contains("high 10")) -> {
                    videoIssue = "H.264 ${media.bitDepthIn}"
                }
                // Non-4:2:0 / 12-bit+ chroma subsampling (yuv422p, yuv444p, rgb, 12-bit)
                pixFmt.contains("422") || pixFmt.contains("444") ||
                    pixFmt.contains("gbr") || pixFmt.contains("rgb") || pixFmt.contains("bgr") ||
                    pixFmt.contains("12") || pixFmt.contains("16") -> {
                    videoIssue = "${vCodec.uppercase(Locale.US)} ($pixFmt)"
                }
                videoMime == null -> {
                    videoIssue = vCodec.uppercase(Locale.US)
                }
                device.probed && !device.decoderMimes.contains(videoMime) -> {
                    videoIssue = "${vCodec.uppercase(Locale.US)} (no device decoder)"
                }
            }
        }

        // 2. Audio stream diagnosis
        var audioIssue: String? = null
        if (media.hasAudio && !aCodec.isNullOrBlank()) {
            val channels = media.audioChannels ?: 2
            when {
                aCodec in UNSUPPORTED_AUDIO_CODECS || aCodec.startsWith("adpcm_") -> {
                    audioIssue = aCodec.uppercase(Locale.US)
                }
                aCodec == "flac" && channels > 2 -> {
                    audioIssue = "FLAC ${channels}ch"
                }
                aCodec in DEVICE_OPTIONAL_AUDIO_CODECS -> {
                    val mimes = DEVICE_OPTIONAL_AUDIO_CODECS.getValue(aCodec)
                    val hasDeviceDecoder = device.probed && mimes.any { it in device.decoderMimes }
                    if (!hasDeviceDecoder) {
                        audioIssue = aCodec.uppercase(Locale.US)
                    }
                }
                aCodec in GUARANTEED_PLATFORM_AUDIO_CODECS -> {
                    val audioMime = GUARANTEED_PLATFORM_AUDIO_CODECS.getValue(aCodec)
                    // audio/raw (PCM) is handled directly by AudioTrack/Media3 without a MediaCodec decoder
                    if (audioMime != "audio/raw" && device.probed && !device.decoderMimes.contains(audioMime)) {
                        audioIssue = aCodec.uppercase(Locale.US)
                    }
                }
                else -> {
                    // Unknown / uncommon audio codec not in Media3's guaranteed or optional table
                    audioIssue = aCodec.uppercase(Locale.US)
                }
            }
        }

        // 3. Container diagnosis
        var containerIssue: String? = null
        val isTrim = config.mode == ProcessingMode.TRIM_VIDEO
        when {
            ext.isBlank() || ext in NATIVE_MP4_CONTAINERS -> Unit
            ext == "flv" && isTrim -> containerIssue = "FLV (non-seekable)"
            ext in UNSUPPORTED_CONTAINERS -> containerIssue = ext.uppercase(Locale.US)
            ext in EXTRACTABLE_NON_MP4_CONTAINERS -> Unit // Media3 can usually extract directly
            else -> containerIssue = ext.uppercase(Locale.US)
        }

        val kind = when {
            videoIssue != null && audioIssue != null -> Media3IssueKind.VIDEO_AND_AUDIO_INCOMPATIBLE
            videoIssue != null -> Media3IssueKind.VIDEO_INCOMPATIBLE
            audioIssue != null -> Media3IssueKind.AUDIO_INCOMPATIBLE
            containerIssue != null -> Media3IssueKind.CONTAINER_ONLY
            else -> Media3IssueKind.COMPATIBLE
        }

        // Video-Only (`-c:a copy`) is eligible when either there is no audio track OR the source
        // audio is already Media3-compatible + MP4-copy-safe AND the source container is not a
        // legacy sync-risk container (AVI/VOB/WMV/ASF/MPG) where copying audio can drift.
        val audioSafeForVideoOnlyMp4Copy = when {
            !media.hasAudio -> true
            audioIssue != null -> false
            ext in LEGACY_SYNC_RISK_CONTAINERS -> false
            aCodec in VIDEO_ONLY_MP4_SAFE_AUDIO_CODECS -> true
            aCodec in setOf("ac3", "eac3") -> {
                val mimes = DEVICE_OPTIONAL_AUDIO_CODECS[aCodec].orEmpty()
                device.probed && mimes.any { it in device.decoderMimes }
            }
            else -> false
        }

        return Media3CompatibilityReport(
            issueKind = kind,
            requiresImmediateRecovery = kind != Media3IssueKind.COMPATIBLE,
            containerName = ext.uppercase(Locale.US).ifBlank { "MP4" },
            videoCodecName = vCodec?.uppercase(Locale.US),
            audioCodecName = aCodec?.uppercase(Locale.US),
            videoIssueTag = videoIssue,
            audioIssueTag = audioIssue,
            containerIssueTag = containerIssue,
            recommendedAudioBitrateKbps = recommendedAudio,
            canUseVideoOnlyPrep = audioSafeForVideoOnlyMp4Copy
        )
    }

    /**
     * Checks whether a runtime Media3/DirectMediaCodec error message points specifically at the
     * audio track rather than container or video.
     */
    fun isAudioSpecificFailure(errorCodeName: String?, errorDetail: String?): Boolean {
        val code = errorCodeName.orEmpty().uppercase(Locale.US)
        val detail = errorDetail.orEmpty().lowercase(Locale.US)
        if (code.contains("AUDIO")) return true
        return detail.contains("audio/") ||
            detail.contains("audio decoder") ||
            detail.contains("audio encoder") ||
            detail.contains("audio track") ||
            detail.contains("audio processing")
    }

    /**
     * Resolves the exact next progressive recovery action when Media3 Turbo cannot proceed:
     * 1) Video+Audio incompatible -> OFFER_VIDEO_AND_AUDIO directly.
     * 2) Video-only incompatible -> OFFER_VIDEO_ONLY when audio is already MP4/Media3-compatible
     *    and sync-safe (skips unnecessary audio re-encoding!), else OFFER_VIDEO_AND_AUDIO.
     * 3) Audio incompatible (or audio-specific runtime error) -> OFFER_AUDIO_CONVERT directly.
     * 4) Container remux hasn't been tried yet -> OFFER_REMUX.
     * 5) Remux tried & failed -> OFFER_AUDIO_CONVERT (if hasAudio) else OFFER_VIDEO_ONLY (if silent).
     * 6) Audio-Only tried & failed -> OFFER_VIDEO_AND_AUDIO.
     * 7) Video-Only tried & failed -> OFFER_VIDEO_AND_AUDIO (if hasAudio) else EXHAUSTED.
     * 8) Video+Audio already tried -> EXHAUSTED.
     */
    fun resolveNextRecoveryStep(
        report: Media3CompatibilityReport,
        hasAudio: Boolean,
        errorCodeName: String?,
        errorDetail: String?,
        hasTriedRemux: Boolean,
        hasTriedAudioConvert: Boolean,
        hasTriedVideoAudioConvert: Boolean,
        hasTriedVideoOnlyConvert: Boolean = false
    ): TurboRecoveryStep {
        if (hasTriedVideoAudioConvert) {
            return TurboRecoveryStep.EXHAUSTED
        }
        // 10-bit hardware encoder limitation should not loop through useless remux/audio-convert
        if (errorCodeName == "ERROR_CODE_10BIT_NOT_PRESERVED") {
            return TurboRecoveryStep.EXHAUSTED
        }
        if (report.issueKind == Media3IssueKind.VIDEO_AND_AUDIO_INCOMPATIBLE) {
            return TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
        }
        if (report.issueKind == Media3IssueKind.VIDEO_INCOMPATIBLE) {
            if (!hasTriedVideoOnlyConvert && report.canUseVideoOnlyPrep) {
                return TurboRecoveryStep.OFFER_VIDEO_ONLY
            }
            return if (hasAudio) TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO else TurboRecoveryStep.EXHAUSTED
        }
        if (hasAudio && !hasTriedAudioConvert &&
            (report.issueKind == Media3IssueKind.AUDIO_INCOMPATIBLE ||
                isAudioSpecificFailure(errorCodeName, errorDetail))
        ) {
            return TurboRecoveryStep.OFFER_AUDIO_CONVERT
        }
        if (!hasTriedRemux) {
            return TurboRecoveryStep.OFFER_REMUX
        }
        if (!hasAudio) {
            return if (!hasTriedVideoOnlyConvert) {
                TurboRecoveryStep.OFFER_VIDEO_ONLY
            } else {
                TurboRecoveryStep.EXHAUSTED
            }
        }
        if (!hasTriedAudioConvert && !hasTriedVideoOnlyConvert) {
            return TurboRecoveryStep.OFFER_AUDIO_CONVERT
        }
        return TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO
    }
}
