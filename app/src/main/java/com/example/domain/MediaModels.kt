package com.example.domain

import android.net.Uri

enum class MediaPickSource {
    GALLERY_VIDEO,
    FILE_PICKER
}

data class MediaInfo(
    val uri: Uri,
    val fileName: String,
    val baseName: String,
    val extension: String,
    val sizeBytes: Long,
    val durationSec: Double?,
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val pixFmt: String?,
    val audioCodec: String?,
    val fps: Double? = null,
    val audioBitrateKbps: Int? = null,
    val audioChannels: Int? = null,
    val videoProfile: String? = null,
    val colorTransfer: String? = null,
    val colorSpace: String? = null,
    val colorPrimaries: String? = null
) {
    val bitDepthIn: String
        get() = when {
            pixFmt?.contains("10") == true -> "10bit"
            pixFmt?.contains("12") == true -> "12bit"
            !pixFmt.isNullOrBlank() -> "8bit"
            videoProfile?.contains("10") == true -> "10bit"
            else -> "8bit"
        }

    val bitShort: String
        get() = when {
            pixFmt?.contains("10") == true -> "10b"
            pixFmt?.contains("12") == true -> "12b"
            !pixFmt.isNullOrBlank() -> "8b"
            videoProfile?.contains("10") == true -> "10b"
            else -> ""
        }

    val isHdrVideo: Boolean
        get() {
            val tr = colorTransfer?.lowercase(java.util.Locale.US).orEmpty()
            val sp = colorSpace?.lowercase(java.util.Locale.US).orEmpty()
            val pr = colorPrimaries?.lowercase(java.util.Locale.US).orEmpty()
            val prof = videoProfile?.lowercase(java.util.Locale.US).orEmpty()
            return tr == "smpte2084" ||
                tr == "arib-std-b67" ||
                pr.startsWith("bt2020") ||
                sp.startsWith("bt2020") ||
                prof.contains("hdr")
        }

    val needsEvenScale: Boolean
        get() = (width != null && width % 2 != 0) || (height != null && height % 2 != 0)

    val hasAudio: Boolean
        get() = !audioCodec.isNullOrBlank()

    val hasVideo: Boolean
        get() = !videoCodec.isNullOrBlank()

    val isLosslessAudio: Boolean
        get() {
            val c = audioCodec?.lowercase(java.util.Locale.US).orEmpty()
            return c in setOf("flac", "alac", "wavpack", "truehd", "mlp", "ape", "tta", "tak") ||
                c.startsWith("pcm_")
        }
}

data class EncoderCapabilities(
    val x264Supports10Bit: Boolean = false,
    val x265Supports10Bit: Boolean = true,
    val probed: Boolean = false
)

enum class ModeCategory(val title: String) {
    TARGET_SIZE("Hedef Boyut"),
    TRIM("Videoyu Kes"),
    OTHER("Daha Fazla")
}

enum class QualityBadgeTone {
    LOSSLESS,
    PARTIAL,
    LOSSY,
    NEUTRAL
}

enum class ProcessingMode(
    val number: Int,
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val shortSubtitle: String,
    val badge: String,
    val qualityTag: String,
    val qualityTone: QualityBadgeTone,
    val category: ModeCategory,
    val requiresAudio: Boolean = false,
    val requiresVideo: Boolean = false
) {
    DISCORD_20MB(
        number = 1,
        title = "Discord",
        shortTitle = "Discord",
        subtitle = "< 20 MB · Gerçek 2-Pass ABR",
        shortSubtitle = "< 20 MB",
        badge = "2-PASS",
        qualityTag = "2-Pass",
        qualityTone = QualityBadgeTone.NEUTRAL,
        category = ModeCategory.TARGET_SIZE,
        requiresVideo = true
    ),
    WHATSAPP_100MB(
        number = 2,
        title = "WhatsApp",
        shortTitle = "WhatsApp",
        subtitle = "< 100 MB · Gerçek 2-Pass ABR",
        shortSubtitle = "< 100 MB",
        badge = "2-PASS",
        qualityTag = "2-Pass",
        qualityTone = QualityBadgeTone.NEUTRAL,
        category = ModeCategory.TARGET_SIZE,
        requiresVideo = true
    ),
    GMAIL_25MB(
        number = 3,
        title = "Gmail",
        shortTitle = "Gmail",
        subtitle = "< 25 MB · Gerçek 2-Pass ABR",
        shortSubtitle = "< 25 MB",
        badge = "2-PASS",
        qualityTag = "2-Pass",
        qualityTone = QualityBadgeTone.NEUTRAL,
        category = ModeCategory.TARGET_SIZE,
        requiresVideo = true
    ),
    CUSTOM_SIZE(
        number = 4,
        title = "Özel Boyut",
        shortTitle = "Özel Boyut",
        subtitle = "İstediğin MB hedefi · 2-Pass",
        shortSubtitle = "MB Gir",
        badge = "MB GİR",
        qualityTag = "2-Pass",
        qualityTone = QualityBadgeTone.NEUTRAL,
        category = ModeCategory.TARGET_SIZE,
        requiresVideo = true
    ),
    TRIM_VIDEO(
        number = 5,
        title = "Videoyu Kes",
        shortTitle = "Videoyu Kes",
        subtitle = "Akıllı Keyframe tarama · Kayıpsız / Hassas",
        shortSubtitle = "Keyframe · Hassas",
        badge = "KEYFRAME",
        qualityTag = "Kayıpsız",
        qualityTone = QualityBadgeTone.LOSSLESS,
        category = ModeCategory.TRIM,
        requiresVideo = true
    ),
    REMUX_FORMAT(
        number = 6,
        title = "Formatı Dönüştür",
        shortTitle = "Formatı Dönüştür",
        subtitle = "%100 kayıpsız MP4 kutu yenileme (-c copy)",
        shortSubtitle = "Kayıpsız MP4",
        badge = "KAYIPSIZ",
        qualityTag = "Kayıpsız",
        qualityTone = QualityBadgeTone.LOSSLESS,
        category = ModeCategory.OTHER,
        requiresVideo = true
    ),
    EXTRACT_AUDIO(
        number = 7,
        title = "Sesi Çıkart",
        shortTitle = "Sesi Çıkart",
        subtitle = "Orijinal (Kayıpsız) / AAC / MP3",
        shortSubtitle = "Orijinal · AAC · MP3",
        badge = "SES",
        qualityTag = "Kayıpsız",
        qualityTone = QualityBadgeTone.LOSSLESS,
        category = ModeCategory.OTHER,
        requiresAudio = true
    ),
    AUDIO_ONLY_AAC(
        number = 8,
        title = "Sadece Sesi Dönüştür",
        shortTitle = "Sadece Sesi Dönüştür",
        subtitle = "Görüntü %100 copy · Ses AAC",
        shortSubtitle = "Kayıpsız",
        badge = "COPY|AAC",
        qualityTag = "Kayıpsız",
        qualityTone = QualityBadgeTone.LOSSLESS,
        category = ModeCategory.OTHER,
        requiresAudio = true,
        requiresVideo = true
    ),
    VIDEO_ONLY_CRF(
        number = 9,
        title = "Sadece Görüntüyü Dönüştür",
        shortTitle = "Sadece Görüntüyü Dönüştür",
        subtitle = "CRF 16/20 · Ses %100 copy",
        shortSubtitle = "Kayıplı",
        badge = "CRF|COPY",
        qualityTag = "Kayıplı",
        qualityTone = QualityBadgeTone.LOSSY,
        category = ModeCategory.OTHER,
        requiresVideo = true
    ),
    VIDEO_AND_AUDIO_CRF(
        number = 10,
        title = "Görüntüyü + Sesi Dönüştür",
        shortTitle = "Görüntüyü + Sesi Dönüştür",
        subtitle = "CRF 16/20 · 8/10-bit · AAC",
        shortSubtitle = "Kayıplı",
        badge = "CRF+AAC",
        qualityTag = "Kayıplı",
        qualityTone = QualityBadgeTone.LOSSY,
        category = ModeCategory.OTHER,
        requiresVideo = true
    ),
    CUSTOM_COMMAND(
        number = 11,
        title = "Özel FFmpeg Komutu",
        shortTitle = "Özel FFmpeg Komutu",
        subtitle = "Tam komut yapıştır (&& zinciri dahil)",
        shortSubtitle = "",
        badge = "KOMUT",
        qualityTag = "",
        qualityTone = QualityBadgeTone.NEUTRAL,
        category = ModeCategory.OTHER
    );

    val formattedNumber: String get() = number.toString().padStart(2, '0')
}

enum class VideoCodecOption(
    val encoder: String,
    val label: String,
    val crf: Int,
    val tag: String
) {
    H264("libx264", "H.264", 16, "H264"),
    H265("libx265", "H.265 (HEVC)", 20, "H265")
}

enum class BitDepthOption(
    val pixFmt: String,
    val label: String,
    val tag: String
) {
    BIT_8("yuv420p", "8-bit", ""),
    BIT_10("yuv420p10le", "10-bit", "_10bit")
}

enum class FpsOption(val label: String, val ffmpegValue: String?) {
    ORIGINAL("Orijinal FPS", null),
    FPS_30("30 FPS", "30")
}

enum class ResolutionOption(
    val label: String,
    val targetShort: Int?,
    val maxLong: Int?
) {
    ORIGINAL("Orijinal", null, null),
    P2160("2160p (4K)", 2160, 3840),
    P1440("1440p (2K)", 1440, 2560),
    P1080("1080p", 1080, 1920),
    P720("720p", 720, 1280),
    P480("480p", 480, 854)
}

enum class PresetOption(val value: String, val label: String) {
    FAST("fast", "fast"),
    MEDIUM("medium", "medium"),
    SLOW("slow", "slow"),
    VERYSLOW("veryslow", "veryslow")
}

enum class TrimCutStrategy(val title: String, val subtitle: String) {
    LOSSLESS_KEYFRAME("Kayıpsız Kes", "Keyframe · Orijinal boyut"),
    PRECISE_REENCODE("Hassas Kes", "Tam saniye · CRF kodlama")
}

enum class TrimSubMode(
    val number: Int,
    val title: String,
    val subtitle: String,
    val requiresLosslessKeyframes: Boolean
) {
    FULL_LOSSLESS(1, "Tam Kayıpsız", "Görüntü + Ses copy (-noaccurate_seek)", true),
    VIDEO_LOSSLESS_AUDIO_AAC(2, "Görüntü Kayıpsız + Ses AAC Değiştir", "5.1 / DTS / EAC3 için uyumlu kesim", true),
    CRF_AUDIO_COPY(3, "Görüntü Değiştir (CRF) + Ses Kayıpsız", "Hassas kesim · Ses %100 kayıpsız", false),
    CRF_AUDIO_AAC(4, "Görüntü + Ses Değiştir (CRF + AAC)", "Hassas kesim · Tam yeniden kodlama", false)
}

enum class TrimTimeChoice(val label: String) {
    SNAP_KEYFRAMES("*KF'lerden Kes (Önerilen)"),
    RAW_USER_TIMES("Yazdığım Ham Süre")
}

enum class AudioExtractFormat(val label: String, val ext: String) {
    ORIGINAL("Orijinal Kodek", ""),
    AAC("AAC (.m4a)", "m4a"),
    MP3("MP3 (.mp3)", "mp3")
}

data class KeyframePointResult(
    val targetSec: Double,
    val prevKf: Double?,
    val nextKf: Double?,
    val recommendedRawSec: Double?,
    val recommendedDisplay: String?,
    val nearbyKeyframes: List<Double>
)

data class TrimScanState(
    val isScanning: Boolean = false,
    val panelExpanded: Boolean = false,
    val startScan: KeyframePointResult? = null,
    val endScan: KeyframePointResult? = null,
    val error: String? = null
)

data class ModeConfigState(
    val mode: ProcessingMode,
    val targetMbInput: String = "20",
    val manualDurationInput: String = "",
    val codec: VideoCodecOption = VideoCodecOption.H264,
    val bitDepth: BitDepthOption = BitDepthOption.BIT_8,
    val fps: FpsOption = FpsOption.ORIGINAL,
    val resolution: ResolutionOption = ResolutionOption.ORIGINAL,
    val preset: PresetOption = PresetOption.MEDIUM,
    val audioBitrateInput: String = "128",
    // Trim specific
    val startTimeInput: String = "",
    val endTimeInput: String = "",
    val trimCutStrategy: TrimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
    val trimSubMode: TrimSubMode = TrimSubMode.FULL_LOSSLESS,
    val trimTimeChoice: TrimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES,
    val selectedStartKfSec: Double? = null,
    val selectedEndKfSec: Double? = null,
    val trimScan: TrimScanState = TrimScanState(),
    // Audio extract specific
    val audioExtractFormat: AudioExtractFormat = AudioExtractFormat.ORIGINAL,
    // Custom command specific
    val customCommandInput: String = ""
)

data class FfmpegStepSpec(
    val label: String,
    val args: List<String>,
    val expectedDurationSec: Double?
)

data class CompletedOutput(
    val id: String,
    val fileName: String,
    val displayPath: String,
    val contentUriString: String,
    val mimeType: String,
    val outputSizeBytes: Long,
    val inputSizeBytes: Long,
    val modeLabel: String,
    val timestampMs: Long
)

enum class TurboRecoveryStep {
    NONE,
    OFFER_REMUX,
    OFFER_AUDIO_CONVERT,
    OFFER_VIDEO_ONLY,
    OFFER_VIDEO_AND_AUDIO,
    EXHAUSTED
}

data class PendingTurboContinuation(
    val sourceMedia: MediaInfo,
    val targetConfig: ModeConfigState
)

sealed interface EncodingState {
    data object Idle : EncodingState

    data class Running(
        val modeTitle: String,
        val outputFileName: String,
        val passIndex: Int,
        val totalPasses: Int,
        val statusMessage: String,
        val currentTimeSec: Double,
        val totalDurationSec: Double?,
        val percent: Int?,
        val speed: String,
        val sizeKb: Long,
        val logs: List<String>,
        val summaryBanner: String,
        val videoSpecsSummary: String? = null,
        val presetLabel: String? = null,
        val isHardwareTurbo: Boolean = false,
        val canSwitchToTurbo: Boolean = false,
        val isTurboPreRemux: Boolean = false,
        val elapsedSec: Double = 0.0
    ) : EncodingState

    data class Success(
        val outputs: List<CompletedOutput>,
        val modeTitle: String,
        val summaryMessage: String,
        val logs: List<String>,
        val isHardwareTurbo: Boolean = false,
        val elapsedSec: Double = 0.0,
        val commandOutput: String? = null
    ) : EncodingState

    data class Failed(
        val modeTitle: String,
        val errorMessage: String,
        val hint: String?,
        val logs: List<String>,
        val wasCancelled: Boolean = false,
        val isHardwareTurbo: Boolean = false,
        val isUnsupportedFormat: Boolean = false,
        val hasTriedRemux: Boolean = false,
        val recoveryStep: TurboRecoveryStep = TurboRecoveryStep.NONE,
        val diagnosisSummary: String? = null,
        val recommendedAudioBitrateKbps: Int = 256,
        val sourceAudioBitrateKbps: Int? = null,
        val sourceAudioCodec: String? = null,
        val isLosslessSourceAudio: Boolean = false,
        val hasSourceAudio: Boolean = true,
        val hasTriedAudioConvert: Boolean = false,
        val hasTriedVideoOnlyConvert: Boolean = false,
        val hasTriedVideoAudioConvert: Boolean = false,
        val rawErrorDetails: String? = null
    ) : EncodingState
}

fun EncodingState.isHardwareTurboState(): Boolean = when (this) {
    is EncodingState.Running -> isHardwareTurbo
    is EncodingState.Success -> isHardwareTurbo
    is EncodingState.Failed -> isHardwareTurbo
    EncodingState.Idle -> false
}

fun supportsMedia3HardwareTurbo(media: MediaInfo, config: ModeConfigState): Boolean {
    if (!media.hasVideo) return false
    return when (config.mode) {
        ProcessingMode.DISCORD_20MB,
        ProcessingMode.WHATSAPP_100MB,
        ProcessingMode.GMAIL_25MB,
        ProcessingMode.CUSTOM_SIZE,
        ProcessingMode.VIDEO_ONLY_CRF,
        ProcessingMode.VIDEO_AND_AUDIO_CRF -> true
        ProcessingMode.TRIM_VIDEO ->
            config.trimSubMode == TrimSubMode.CRF_AUDIO_COPY ||
                config.trimSubMode == TrimSubMode.CRF_AUDIO_AAC
        else -> false
    }
}
