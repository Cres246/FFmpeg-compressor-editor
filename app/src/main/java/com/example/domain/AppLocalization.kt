package com.example.domain

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val nativeName: String) {
    TR("tr", "Türkçe"),
    EN("en", "English")
}

enum class LanguagePreference(val storageKey: String) {
    SYSTEM("system"),
    TR("tr"),
    EN("en");

    companion object {
        fun fromStorageKey(key: String?): LanguagePreference = when (key) {
            "tr" -> TR
            "en" -> EN
            else -> SYSTEM
        }
    }
}

/**
 * Process-scoped language controller:
 * - Automatically detects system language on startup: Turkish (`tr`) -> TR, any other locale -> EN.
 * - Persists explicit user overrides in SharedPreferences for instant 0ms cold-start reads.
 * - Exposes reactive StateFlows for Compose UI and direct getters for ForegroundService / helpers.
 */
object AppLanguageController {
    private const val PREFS_NAME = "fflab_language_prefs"
    private const val KEY_LANGUAGE_PREF = "language_preference_v1"

    private val _preference = MutableStateFlow(LanguagePreference.SYSTEM)
    val preference: StateFlow<LanguagePreference> = _preference.asStateFlow()

    private val _language = MutableStateFlow(detectSystemLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    @Volatile
    private var initialized = false

    fun detectSystemLanguage(): AppLanguage {
        val sysLocale = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            }
        }.getOrNull() ?: Locale.getDefault()

        return if (sysLocale.language.equals("tr", ignoreCase = true)) {
            AppLanguage.TR
        } else {
            AppLanguage.EN
        }
    }

    fun init(context: Context) {
        if (initialized) {
            if (_preference.value == LanguagePreference.SYSTEM) {
                _language.value = detectSystemLanguage()
            }
            return
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = LanguagePreference.fromStorageKey(prefs.getString(KEY_LANGUAGE_PREF, null))
        _preference.value = saved
        _language.value = resolveEffectiveLanguage(saved)
        initialized = true
    }

    fun setPreference(context: Context, pref: LanguagePreference) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE_PREF, pref.storageKey).apply()
        _preference.value = pref
        _language.value = resolveEffectiveLanguage(pref)
        initialized = true
    }

    fun currentLanguage(): AppLanguage = _language.value

    fun currentStrings(): AppStrings = stringsFor(_language.value)

    private fun resolveEffectiveLanguage(pref: LanguagePreference): AppLanguage = when (pref) {
        LanguagePreference.SYSTEM -> detectSystemLanguage()
        LanguagePreference.TR -> AppLanguage.TR
        LanguagePreference.EN -> AppLanguage.EN
    }
}

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { EnglishStrings }

fun stringsFor(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.TR -> TurkishStrings
    AppLanguage.EN -> EnglishStrings
}

data class AppStrings(
    val language: AppLanguage,
    // Top bar & language menu
    val languageSelectorCd: String,
    val languageAutoSystem: String,
    val historyTitle: String,
    val historyClear: String,
    val historyClearCd: String,
    val historyEmpty: String,
    val batteryOptDialogTitle: String,
    val batteryOptDialogBody: String,
    val batteryOptAllowButton: String,
    val batteryOptLaterButton: String,

    // Common actions & intent choosers
    val actionBack: String,
    val actionCancel: String,
    val actionStart: String,
    val actionOpen: String,
    val actionShare: String,
    val actionNewTask: String,
    val actionHome: String,
    val actionBackToSettings: String,
    val toastNoAppToOpenFile: String,
    val toastShareFailed: String,
    val chooserPlayVideoWith: String,
    val toastNoAppToPlayVideo: String,

    // Media hero card
    val analyzingMedia: String,
    val selectSourceMedia: String,
    val pickGalleryVideo: String,
    val pickFile: String,
    val changeFile: String,
    val changeFileCd: String,
    val audioOnlyTag: String,
    val audioTrackPrefix: String,
    val noneLabel: String,
    val evenDimensionFixChip: String,

    // Home screen badges & categories
    val noVideoBadge: String,
    val noAudioBadge: String,
    val catTargetSize: String,
    val catTrim: String,
    val catOther: String,

    // Mode names, subtitles & badges
    val modeCustomSizeTitle: String,
    val modeCustomSizeShortSub: String,
    val modeCustomSizeSub: String,
    val modeDiscordSub: String,
    val modeWhatsappSub: String,
    val modeGmailSub: String,
    val modeTrimTitle: String,
    val modeTrimSub: String,
    val modeTrimShortSub: String,
    val modeRemuxTitle: String,
    val modeRemuxShortTitle: String,
    val modeRemuxSub: String,
    val modeExtractAudioTitle: String,
    val modeExtractAudioSub: String,
    val modeAudioOnlyTitle: String,
    val modeAudioOnlySub: String,
    val modeVideoOnlyTitle: String,
    val modeVideoOnlySub: String,
    val modeVideoAndAudioTitle: String,
    val modeVideoAndAudioSub: String,
    val modeCustomCmdTitle: String,
    val modeCustomCmdSub: String,
    val badgeLossless: String,
    val badgeLossy: String,

    // Encoder controls
    val codecSectionTitle: String,
    val codecSectionSub: (codec: String, bitDepth: String) -> String,
    val colorBitDepthLabel: String,
    val bit8Compatible: String,
    val bit8Default: String,
    val bit10Preserve: String,
    val nonNativeTenBitWarning: (sourceBitDepth: String) -> String,
    val fpsResSectionTitle: String,
    val fpsResSectionSub: String,
    val recommend30Fps720pText: (vkbps: Int) -> String,
    val fpsOriginalLabel: String,
    val fpsKeepSub: String,
    val targetResolutionLabel: String,
    val resOriginalLabel: String,
    val activeFilterPrefix: String,
    val presetSectionTitle: String,
    val presetCrfSub: (recommended: String) -> String,
    val presetTargetSizeSub: (recommended: String) -> String,
    val presetTargetSizeHighlight: String,
    val presetVerySlowNote: String,
    val audioBitrateTitle: String,
    val audioBitrateExtractTitle: (format: String) -> String,
    val audioBitrateSub: (activeKbps: Int) -> String,
    val customBitrateLabel: String,
    val outputFileNameHeader: String,
    val ffmpegCommandPreview: String,
    val actionHide: String,
    val actionShow: String,

    // Two-pass target card
    val twoPassTitle: String,
    val twoPassSub: String,
    val targetMbInputLabel: String,
    val manualDurationLabel: String,
    val secondsUnitShort: String,
    val twoPassSummaryLine: (mbTag: String, codecTag: String, bitTag: String, vkbps: Int, abps: Int, preset: String) -> String,
    val lowBitrateWarning: String,

    // Trim screen
    val previewStartTag: String,
    val previewEndTag: String,
    val previewFrameCd: (tag: String, time: String) -> String,
    val previewFrameFallbackCd: (tag: String) -> String,
    val playVideoButton: String,
    val trimStartLabel: String,
    val trimEndLabel: String,
    val trimStartPlaceholder: String,
    val trimStartSummaryDefault: String,
    val trimEndPlaceholder: String,
    val trimEndExceedsDurationWarning: (durText: String) -> String,
    val trimEndOutOfBoundsBadge: String,
    val trimStrategyLosslessTitle: String,
    val trimStrategyLosslessSub: String,
    val trimStrategyPreciseTitle: String,
    val trimStrategyPreciseSub: String,
    val trimLosslessInfo: String,
    val trimPreciseInfo: String,
    val scanningKeyframes: String,
    val showKeyframesButton: String,
    val startKeyframesLabel: String,
    val endKeyframesLabel: String,
    val noNearbyKeyframes: String,
    val selectedKeyframeCd: String,
    val trimModeTitle: String,
    val trimModeSub: String,
    val trimModeDisabledCd: String,
    val trimSubFullLosslessTitle: String,
    val trimSubFullLosslessSub: String,
    val trimSubVideoLosslessAudioAacTitle: String,
    val trimSubVideoLosslessAudioAacSub: String,
    val trimSubCrfAudioCopyTitle: String,
    val trimSubCrfAudioCopySub: String,
    val trimSubCrfAudioAacTitle: String,
    val trimSubCrfAudioAacSub: String,

    // Remux / Audio-Only / Video-Only / Audio Extract / Custom Command cards
    val remuxCardTitle: String,
    val remuxCardSub: String,
    val remuxCardHighlight: String,
    val audioOnlyCardTitle: String,
    val audioOnlyCardSub: (codec: String) -> String,
    val audioOnlyCardBody: String,
    val videoOnlyAudioTitle: String,
    val videoOnlyAudioCopySub: (codec: String) -> String,
    val videoOnlyNoAudioSub: String,
    val videoOnlyCrfBody: String,
    val audioExtractTitle: String,
    val audioExtractSub: String,
    val audioExtractOriginalOption: (codec: String, ext: String) -> String,
    val audioExtractOriginalLabel: String,
    val customCmdTitle: String,
    val customCmdSub: String,
    val customCmdShortcutsLabel: String,
    val customCmdQuickRemuxChip: String,
    val customCmdDefaultOutput: String,
    val customCmdInputLabel: String,

    // Processing screen
    val passBadge: (passIndex: Int, totalPasses: Int) -> String,
    val processingBadge: String,
    val formatPercent: (percent: Int) -> String,
    val cpuBoostWithFreq: (ghz: String, cores: Int) -> String,
    val cpuBoostGeneric: (cores: Int) -> String,
    val wakeLockActiveSuffix: String,
    val enableMagicCapsuleButton: String,
    val successCd: String,
    val successTitle: String,
    val successMediaStoreNote: String,
    val smallerPercentBadge: (percent: Int) -> String,
    val failedCancelledTitle: String,
    val failedErrorTitle: String,
    val logConsoleHeader: (count: Int) -> String,
    val logConsoleEmpty: String,

    // Validation & ViewModel errors
    val errAnalyzeFailed: (detail: String) -> String,
    val errSelectMediaFirst: String,
    val errNoVideoTrack: String,
    val errNoAudioTrack: String,
    val errEnterTrimTimeFirst: String,
    val errInvalidStartTime: String,
    val errInvalidEndTime: String,
    val errEndBeforeStart: String,
    val errKeyframeRequiresVideo: String,
    val errKeyframeScanFailed: (msg: String) -> String,
    val errInvalidTargetMb: String,
    val errEnterManualDuration: String,
    val errNoTrimRangeEntered: String,
    val errInvalidTimeFormat: String,
    val errEnterCustomCommand: String,

    // Foreground service, logs, banners, hints & engine errors
    val notifChannelName: String,
    val notifChannelDesc: String,
    val notifPreparing: String,
    val logTagStarted: String,
    val logTagStep: (passNum: Int, totalPasses: Int) -> String,
    val logTagError: String,
    val logTagCompleted: String,
    val errFfmpegExitCode: (code: Int) -> String,
    val errOutputNotCreated: String,
    val errUnexpected: String,
    val successOutputSize: (size: String) -> String,
    val errCustomCmdEmpty: String,
    val hintCustomCmdExample: String,
    val customCmdStepMulti: (passNum: Int, totalSteps: Int) -> String,
    val customCmdStepSingle: String,
    val customCmdOutputLabel: String,
    val errCustomCmdFailed: (code: Int) -> String,
    val hintCustomCmdSyntax: (ext: String) -> String,
    val successCustomCmdDoneNoFiles: String,
    val successCustomCmdSavedFiles: (count: Int, totalSize: String) -> String,
    val bannerTrimRange: (start: String, end: String, subMode: String) -> String,
    val bannerRemux: String,
    val bannerAudioOnly: (ab: Int) -> String,
    val bannerVideoOnly: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String,
    val bannerSilentAudio: String,
    val bannerExtractAudio: (formatLabel: String) -> String,
    val bannerCustomCmd: String,
    val hintTrimLosslessAudio: String,
    val hintVideoOnlyAudio: String,
    val hintRemuxCompat: String,
    val errSafReadFailed: String,
    val errUnknown: String,
    val errMediaStoreInsertFailed: String,
    val errOutputStreamFailed: String,
    val errSaveFailed: (detail: String) -> String,
    val errUnknownMediaStore: String,

    // FfmpegMath step labels, telemetry & filename suffixes
    val telemetryPrefix: String,
    val stepPass1: String,
    val stepPass2: String,
    val stepTrimLossless: String,
    val stepTrimVideoLosslessAudioAac: String,
    val stepTrimCrfAudioCopy: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String,
    val stepTrimCrfAudioAac: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String,
    val stepRemux: String,
    val stepAudioOnly: (ab: Int) -> String,
    val stepVideoOnly: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String,
    val stepExtractOriginal: (codec: String, ext: String) -> String,
    val stepExtractAacCopy: String,
    val stepExtractAacTranscode: (ab: Int) -> String,
    val stepExtractMp3Copy: String,
    val stepExtractMp3Transcode: (ab: Int) -> String,
    val customCommandOutputPlaceholder: String,
    val fileTagClip: String,
    val fileTagSilent: String,
    val fileTagOriginal: String,
    val fileTagAudio: String
)

val TurkishStrings = AppStrings(
    language = AppLanguage.TR,
    languageSelectorCd = "Dil seç",
    languageAutoSystem = "Sistem Dili (Otomatik)",
    historyTitle = "Son Çıktılar",
    historyClear = "Temizle",
    historyClearCd = "Geçmişi temizle",
    historyEmpty = "Henüz işlenmiş dosya yok. Bir video seçerek başlayın.",
    batteryOptDialogTitle = "Pil Tasarrufunu Yoksay",
    batteryOptDialogBody = "FFmpeg uzun video kodlamalarında işlemci çekirdeklerini tam performansta kullanabilmek ve arka planda kısıtlanmamak için pil optimizasyonunun kapatılmasını önerir.",
    batteryOptAllowButton = "İzin Ver",
    batteryOptLaterButton = "Sonra",

    actionBack = "Geri",
    actionCancel = "İptal Et",
    actionStart = "İşlemi Başlat",
    actionOpen = "Oynat / Aç",
    actionShare = "Paylaş",
    actionNewTask = "Yeni İşlem",
    actionHome = "Ana Menü",
    actionBackToSettings = "Ayarlara Dön",
    toastNoAppToOpenFile = "Dosyayı açacak uygulama bulunamadı.",
    toastShareFailed = "Paylaşım başlatılamadı.",
    chooserPlayVideoWith = "Hangi uygulamada oynatılsın?",
    toastNoAppToPlayVideo = "Videoyu açacak uygulama bulunamadı.",

    analyzingMedia = "Medya analiz ediliyor...",
    selectSourceMedia = "Kaynak Medya Seç",
    pickGalleryVideo = "Galeri (Video)",
    pickFile = "Dosya Seç",
    changeFile = "Değiştir",
    changeFileCd = "Dosyayı değiştir",
    audioOnlyTag = "SES",
    audioTrackPrefix = "Ses",
    noneLabel = "Yok",
    evenDimensionFixChip = "Tek Piksel Koruması",

    noVideoBadge = "Video yok",
    noAudioBadge = "Ses izi yok",
    catTargetSize = "Hedef Boyut",
    catTrim = "Videoyu Kes",
    catOther = "Diğer İşlemler",

    modeCustomSizeTitle = "Özel Boyut",
    modeCustomSizeShortSub = "MB Gir",
    modeCustomSizeSub = "İstediğin MB hedefi · 2-Pass",
    modeDiscordSub = "< 20 MB · Gerçek 2-Pass ABR",
    modeWhatsappSub = "< 100 MB · Gerçek 2-Pass ABR",
    modeGmailSub = "< 25 MB · Gerçek 2-Pass ABR",
    modeTrimTitle = "Videoyu Kes",
    modeTrimSub = "Akıllı Keyframe tarama · Kayıpsız / Hassas",
    modeTrimShortSub = "Keyframe · Hassas",
    modeRemuxTitle = "Sadece Format Değiştir",
    modeRemuxShortTitle = "Format Değiştir",
    modeRemuxSub = "%100 kayıpsız MP4 kutu yenileme (-c copy)",
    modeExtractAudioTitle = "Sesi Çıkart",
    modeExtractAudioSub = "Orijinal (Kayıpsız) / AAC / MP3",
    modeAudioOnlyTitle = "Sadece Ses Değiştir",
    modeAudioOnlySub = "Görüntü %100 copy · Ses AAC",
    modeVideoOnlyTitle = "Sadece Görüntü Değiştir",
    modeVideoOnlySub = "CRF 16/20 · Ses %100 copy",
    modeVideoAndAudioTitle = "Görüntü + Ses Değiştir",
    modeVideoAndAudioSub = "CRF 16/20 · 8/10-bit · AAC",
    modeCustomCmdTitle = "Özel FFmpeg Komutu",
    modeCustomCmdSub = "Tam komut yapıştır (&& zinciri dahil)",
    badgeLossless = "Kayıpsız",
    badgeLossy = "Kayıplı",

    codecSectionTitle = "Video Kodeği & Renk Derinliği",
    codecSectionSub = { codec, bitDepth -> "Girdi: $codec · $bitDepth · H.265 çıktılar otomatik -tag:v hvc1 alır" },
    colorBitDepthLabel = "Renk Bit Derinliği",
    bit8Compatible = "8-bit (Uyumlu)",
    bit8Default = "8-bit (Varsayılan)",
    bit10Preserve = "10-bit (Koru)",
    nonNativeTenBitWarning = { src -> "Uyarı: Orijinal kaynak video $src (10-bit değil). 8-bit kaynaktan 10-bit kodlamak ekstra renk derinliği katmaz, kodlama süresini uzatır ve bazı cihazlarda uyumluluğu düşürebilir." },
    fpsResSectionTitle = "FPS & Akıllı Çözünürlük",
    fpsResSectionSub = "3 yönlü oran koruması: Dikey (9:16 / 4:5), Sinema (2.39:1) ve 16:9 otomatik algılanır",
    recommend30Fps720pText = { vkbps -> "Video uzunluğuna göre bitrate aşırı düşüyor (${vkbps}k). Daha net görüntü için 30 FPS ve 720p tavsiye edilir." },
    fpsOriginalLabel = "Orijinal FPS",
    fpsKeepSub = "Koru",
    targetResolutionLabel = "Hedef Çözünürlük",
    resOriginalLabel = "Orijinal",
    activeFilterPrefix = "Aktif Filtre:",
    presetSectionTitle = "Kodlama Hızı (Preset)",
    presetCrfSub = { rec -> "Önerilen: $rec · CRF dönüşümünde daha yavaş preset daha küçük dosya boyutu üretir, kalite değişmez." },
    presetTargetSizeSub = { rec -> "Önerilen: $rec" },
    presetTargetSizeHighlight = "Daha yavaş preset görüntü kalitesini artırır; daha küçük dosya boyutu üreteceği için sıkıştırma kalitesini artırır.",
    presetVerySlowNote = "Not: slow ile veryslow arasındaki gerçek kalite/verimlilik farkı yalnızca ~%2–4 civarındadır; ancak kodlama süresi yaklaşık 2–3 kat uzar.",
    audioBitrateTitle = "Ses Bitrate (AAC · Kayıplı)",
    audioBitrateExtractTitle = { fmt -> "Çıktı Ses Bitrate ($fmt)" },
    audioBitrateSub = { kbps -> "32k – 768k aralığı desteklenir (5.1 surround için 320k/384k+ dahil) · Aktif: ${kbps}k" },
    customBitrateLabel = "Özel Bitrate (32..768 kbps)",
    outputFileNameHeader = "ÇIKTI DOSYA ADI",
    ffmpegCommandPreview = "FFmpeg Komutu Önizleme",
    actionHide = "Gizle",
    actionShow = "Göster",

    twoPassTitle = "Gerçek 2-Pass Hedef Boyut Hesabı",
    twoPassSub = "Tek seferlik %4 güvenlik payı (MB × 8000 × 0.96) ile kapsayıcı taşması önlenir",
    targetMbInputLabel = "Hedef Boyut (MB) · Örn: 1, 8, 25",
    manualDurationLabel = "Süre okunamadı · Saniye olarak girin",
    secondsUnitShort = "sn",
    twoPassSummaryLine = { mb, codec, bit, vkbps, abps, preset ->
        "Hedef: < $mb MB  |  ${codec}${bit} ${vkbps}k  |  Ses: ${abps}k  |  $preset"
    },
    lowBitrateWarning = "Not: Hedef/ses oranına göre video bitrati çok düşük, boyut aşabilir.",

    previewStartTag = "BAŞLANGIÇ",
    previewEndTag = "BİTİŞ",
    previewFrameCd = { tag, time -> "$tag karesi ($time)" },
    previewFrameFallbackCd = { tag -> "$tag karesi" },
    playVideoButton = "Videoyu Oynat",
    trimStartLabel = "Başlangıç",
    trimEndLabel = "Bitiş",
    trimStartPlaceholder = "00:00 / sn (ör. 15)",
    trimStartSummaryDefault = "00:00",
    trimEndPlaceholder = "Son",
    trimEndExceedsDurationWarning = { dur -> "Bitiş süresi video uzunluğunu ($dur) aşıyor!" },
    trimEndOutOfBoundsBadge = "SÜRE AŞILDI",
    trimStrategyLosslessTitle = "Kayıpsız Kes",
    trimStrategyLosslessSub = "Keyframe · Orijinal boyut",
    trimStrategyPreciseTitle = "Hassas Kes",
    trimStrategyPreciseSub = "Tam saniye · CRF kodlama",
    trimLosslessInfo = "Keyframe noktalarından kayıpsız kesilir; görüntü yeniden kodlanmadığı için orijinal kalır ve dosya boyutu kesilen süre oranında aynı kalır.",
    trimPreciseInfo = "Video tam girdiğiniz saniyeden yeniden kodlanacağı için dosya boyutu epey büyüyebilir. CRF 16 (HEVC için CRF 20) ile kodlandığından neredeyse gözle görülemeyecek seviyede görüntü kaybı olur.",
    scanningKeyframes = "Keyframe noktaları taranıyor...",
    showKeyframesButton = "Keyframe Noktalarını Göster",
    startKeyframesLabel = "Başlangıç Keyframe'leri",
    endKeyframesLabel = "Bitiş Keyframe'leri",
    noNearbyKeyframes = "Yakında keyframe bulunamadı",
    selectedKeyframeCd = "Seçili keyframe",
    trimModeTitle = "Kesim Modu",
    trimModeSub = "Kayıpsız kesimler -noaccurate_seek + -avoid_negative_ts make_zero kullanır",
    trimModeDisabledCd = "Hassas kesimde seçilemez",
    trimSubFullLosslessTitle = "Tam Kayıpsız",
    trimSubFullLosslessSub = "Görüntü + Ses copy (-noaccurate_seek)",
    trimSubVideoLosslessAudioAacTitle = "Görüntü Kayıpsız + Ses AAC Değiştir",
    trimSubVideoLosslessAudioAacSub = "5.1 / DTS / EAC3 için uyumlu kesim",
    trimSubCrfAudioCopyTitle = "Görüntü Değiştir (CRF) + Ses Kayıpsız",
    trimSubCrfAudioCopySub = "Hassas kesim · Ses %100 kayıpsız",
    trimSubCrfAudioAacTitle = "Görüntü + Ses Değiştir (CRF + AAC)",
    trimSubCrfAudioAacSub = "Hassas kesim · Tam yeniden kodlama",

    remuxCardTitle = "%100 Kayıpsız MP4 Paketleme",
    remuxCardSub = "Görüntü ve sese dokunmadan (-c copy) MP4 kabı yenilenir, moov başlığı dosya başına alınır (+faststart) ve uyumsuz altyazı/veri izleri elenir (-sn -dn).",
    remuxCardHighlight = "Saniyeler içinde tamamlanır · Sıfır kalite kaybı",
    audioOnlyCardTitle = "Görüntü %100 Kayıpsız Kopyalanır",
    audioOnlyCardSub = { codec -> "Yalnızca ses akışı ($codec) seçilen bitrate'te AAC'ye çevrilir (-c:v copy)." },
    audioOnlyCardBody = "Opus, DTS, AC3 veya EAC3 sesli videoları telefon, WhatsApp ve MP4 oynatıcılarla tam uyumlu yapmak için idealdir.",
    videoOnlyAudioTitle = "Ses Akışı",
    videoOnlyAudioCopySub = { codec -> "Kaynak ses ($codec) yeniden kodlanmadan %100 kayıpsız kopyalanır (-c:a copy)." },
    videoOnlyNoAudioSub = "Videoda ses izi yok (-an).",
    videoOnlyCrfBody = "H.264 için CRF 16, H.265 için buna denk arşiv kalitesi CRF 20 uygulanır.",
    audioExtractTitle = "Ses Çıkarma Formatı",
    audioExtractSub = "Kaynak ses zaten AAC veya MP3 ise yeniden kodlanmadan %100 kayıpsız kopyalanır",
    audioExtractOriginalOption = { codec, ext -> "Orijinal ($codec → .$ext)" },
    audioExtractOriginalLabel = "Orijinal Kodek",
    customCmdTitle = "Özel FFmpeg Komutu (Tam Komut)",
    customCmdSub = "Çoklu && zincir komutları dahil · Oluşan çıktılar otomatik Movies/FFmpeg klasörüne taşınır",
    customCmdShortcutsLabel = "Girdi kısayolları (dokunarak ekle):",
    customCmdQuickRemuxChip = "Hızlı Remux",
    customCmdDefaultOutput = "cikti.mp4",
    customCmdInputLabel = "FFmpeg Komutu",

    passBadge = { p, t -> "GEÇİŞ $p/$t" },
    processingBadge = "İŞLENİYOR",
    formatPercent = { pct -> "%$pct" },
    cpuBoostWithFreq = { ghz, cores -> "Prime ($ghz) öncelikli · $cores çekirdek tam güç" },
    cpuBoostGeneric = { cores -> "Prime çekirdek öncelikli · $cores çekirdek tam güç" },
    wakeLockActiveSuffix = "Uyandırma kilidi aktif.",
    enableMagicCapsuleButton = "Magic Capsule / Canlı Güncelleme İznini Aç",
    successCd = "Başarılı",
    successTitle = "İşlem Tamamlandı",
    successMediaStoreNote = "Galeri ve Son Dosyalar için MediaStore anında güncellendi.",
    smallerPercentBadge = { pct -> "%$pct daha küçük" },
    failedCancelledTitle = "İşlem İptal Edildi",
    failedErrorTitle = "Hata Oluştu",
    logConsoleHeader = { count -> "Durum ve Hata Günlüğü ($count)" },
    logConsoleEmpty = "Başlatıldı, aşama değişiklikleri ve varsa hatalar burada gösterilir.",

    errAnalyzeFailed = { detail -> "Dosya analiz edilemedi: $detail" },
    errSelectMediaFirst = "Önce yukarıdan bir video veya dosya seçin.",
    errNoVideoTrack = "Bu dosyada video akışı bulunamadı! Sesi Çıkart veya Özel Komut kullanın.",
    errNoAudioTrack = "Bu videoda ses izi bulunamadı!",
    errEnterTrimTimeFirst = "Önce başlangıç veya bitiş zamanı girin.",
    errInvalidStartTime = "Başlangıç zamanı geçersiz (Örn: 00:01:00 veya 01:30).",
    errInvalidEndTime = "Bitiş zamanı geçersiz (Örn: 00:02:32 veya 02:32).",
    errEndBeforeStart = "Hata: Bitiş zamanı başlangıçtan büyük olmalı!",
    errKeyframeRequiresVideo = "Bu dosyada video akışı yok, keyframe taraması yalnızca video için çalışır.",
    errKeyframeScanFailed = { msg -> "Keyframe taraması başarısız: $msg" },
    errInvalidTargetMb = "Geçerli bir MB değeri girin (Örn: 1, 8, 25).",
    errEnterManualDuration = "Video süresi okunamadı, lütfen süreyi saniye olarak girin.",
    errNoTrimRangeEntered = "Başlangıç veya bitiş zamanı girmediniz!",
    errInvalidTimeFormat = "Zaman formatı hatalı (Örn: 01:30 veya 00:01:30).",
    errEnterCustomCommand = "Çalıştırmak için bir FFmpeg komutu girin.",

    notifChannelName = "Medya İşleme",
    notifChannelDesc = "FFmpeg dönüştürme ve kesim ilerleme bildirimi",
    notifPreparing = "Hazırlanıyor...",
    logTagStarted = "[Başlatıldı]",
    logTagStep = { p, t -> "[Aşama $p/$t]" },
    logTagError = "[Hata]",
    logTagCompleted = "[Tamamlandı]",
    errFfmpegExitCode = { code -> "FFmpeg hata kodu ile durdu (kod: $code)." },
    errOutputNotCreated = "Çıktı dosyası oluşturulamadı.",
    errUnexpected = "Beklenmeyen bir hata oluştu.",
    successOutputSize = { size -> "Başarılı! Çıktı Boyutu: $size" },
    errCustomCmdEmpty = "Geçerli bir FFmpeg komutu girilmedi.",
    hintCustomCmdExample = "Örnek: ffmpeg -i \"\$IN\" -c:v libx264 -crf 20 cikti.mp4",
    customCmdStepMulti = { p, t -> "Komut $p/$t çalıştırılıyor" },
    customCmdStepSingle = "Özel FFmpeg komutu çalıştırılıyor",
    customCmdOutputLabel = "Özel Komut",
    errCustomCmdFailed = { code -> "Özel FFmpeg komutu başarısız oldu (kod: $code)." },
    hintCustomCmdSyntax = { ext -> "Komut sözdizimini ve girdi kısayollarını (\"\$IN\" veya input.$ext) kontrol edin." },
    successCustomCmdDoneNoFiles = "Başarılı! Komut tamamlandı.",
    successCustomCmdSavedFiles = { count, total -> "Başarılı! $count dosya kaydedildi ($total)" },
    bannerTrimRange = { s, e, sub -> "Aralık: $s → $e | $sub" },
    bannerRemux = "Kayıpsız MP4 kutu yenileme (-c copy +faststart)",
    bannerAudioOnly = { ab -> "Görüntü %100 copy | Ses: ${ab}k AAC" },
    bannerVideoOnly = { codec, bit, crf, preset -> "${codec}${bit} CRF $crf ($preset) | Ses copy" },
    bannerSilentAudio = "Sessiz",
    bannerExtractAudio = { fmt -> "Ses Çıkarma · $fmt" },
    bannerCustomCmd = "Özel FFmpeg Komutu (Çıktılar otomatik taşınır)",
    hintTrimLosslessAudio = "İpucu: Ses kodeği MP4 kutusuna uymuyorsa (ör. Opus/DTS/EAC3) 2. kesim modunu (Görüntü Kayıpsız + Ses AAC Değiştir) seçin.",
    hintVideoOnlyAudio = "İpucu: Ses kodeği MP4 kutusuna uymuyorsa 10 · Görüntü + Ses Değiştir seçeneğini kullanın.",
    hintRemuxCompat = "İpucu: Ses veya video kodeği MP4 kabıyla uyumsuzsa 08 · Sadece Ses Değiştir veya 10 · Görüntü + Ses Değiştir seçeneğini deneyin.",
    errSafReadFailed = "Dosya okuma erişimi alınamadı (SAF)",
    errUnknown = "Bilinmeyen hata",
    errMediaStoreInsertFailed = "MediaStore kaydı oluşturulamadı",
    errOutputStreamFailed = "Çıktı akışı açılamadı",
    errSaveFailed = { detail -> "Dosya kaydedilemedi: $detail" },
    errUnknownMediaStore = "Bilinmeyen MediaStore hatası",

    telemetryPrefix = "Süre",
    stepPass1 = "1/2 Geçiş (Analiz ediliyor)",
    stepPass2 = "2/2 Geçiş (Kodlanıyor)",
    stepTrimLossless = "Kayıpsız kesiliyor (-noaccurate_seek)",
    stepTrimVideoLosslessAudioAac = "Görüntü kayıpsız + Ses AAC kesiliyor",
    stepTrimCrfAudioCopy = { codec, bit, crf, preset -> "Kesilip ${codec}${bit} CRF $crf ($preset | ses copy)" },
    stepTrimCrfAudioAac = { codec, bit, crf, preset -> "Kesilip ${codec}${bit} CRF $crf ($preset) + AAC" },
    stepRemux = "Kutu / format yenileniyor (-c copy)",
    stepAudioOnly = { ab -> "Görüntü copy · Ses ${ab}k AAC" },
    stepVideoOnly = { codec, bit, crf, preset -> "Görüntü ${codec}${bit} CRF $crf ($preset | ses copy)" },
    stepExtractOriginal = { codec, ext -> "Orijinal ses (%100 kayıpsız: $codec → .$ext)" },
    stepExtractAacCopy = "Ses zaten AAC · %100 kayıpsız kopyalanıyor",
    stepExtractAacTranscode = { ab -> "Ses ${ab}k AAC olarak çıkarılıyor" },
    stepExtractMp3Copy = "Ses zaten MP3 · %100 kayıpsız kopyalanıyor",
    stepExtractMp3Transcode = { ab -> "Ses ${ab}k MP3 olarak çıkarılıyor" },
    customCommandOutputPlaceholder = "Komutta belirtilen çıktı",
    fileTagClip = "Kesit",
    fileTagSilent = "Sessiz",
    fileTagOriginal = "Orijinal",
    fileTagAudio = "Ses"
)

val EnglishStrings = AppStrings(
    language = AppLanguage.EN,
    languageSelectorCd = "Change language",
    languageAutoSystem = "System Language (Auto)",
    historyTitle = "Recent Outputs",
    historyClear = "Clear",
    historyClearCd = "Clear history",
    historyEmpty = "No processed files yet. Select a video to get started.",
    batteryOptDialogTitle = "Ignore Battery Optimizations",
    batteryOptDialogBody = "Disable battery optimization so FFmpeg can run CPU cores at full performance and avoid being throttled or stopped by the system during background encodings.",
    batteryOptAllowButton = "Allow",
    batteryOptLaterButton = "Later",

    actionBack = "Back",
    actionCancel = "Cancel",
    actionStart = "Start Processing",
    actionOpen = "Play / Open",
    actionShare = "Share",
    actionNewTask = "New Task",
    actionHome = "Home",
    actionBackToSettings = "Back to Settings",
    toastNoAppToOpenFile = "No app found to open this file.",
    toastShareFailed = "Unable to start sharing.",
    chooserPlayVideoWith = "Play video with",
    toastNoAppToPlayVideo = "No app found to play this video.",

    analyzingMedia = "Analyzing media...",
    selectSourceMedia = "Select Source Media",
    pickGalleryVideo = "Gallery (Video)",
    pickFile = "Pick File",
    changeFile = "Change",
    changeFileCd = "Change source file",
    audioOnlyTag = "AUDIO",
    audioTrackPrefix = "Audio",
    noneLabel = "None",
    evenDimensionFixChip = "Even-Dimension Fix",

    noVideoBadge = "No video",
    noAudioBadge = "No audio",
    catTargetSize = "Target Size",
    catTrim = "Trim Video",
    catOther = "More Tools",

    modeCustomSizeTitle = "Custom Size",
    modeCustomSizeShortSub = "Enter MB",
    modeCustomSizeSub = "Custom MB target · 2-Pass ABR",
    modeDiscordSub = "< 20 MB · True 2-Pass ABR",
    modeWhatsappSub = "< 100 MB · True 2-Pass ABR",
    modeGmailSub = "< 25 MB · True 2-Pass ABR",
    modeTrimTitle = "Trim Video",
    modeTrimSub = "Smart keyframe scan · Lossless / Frame-accurate",
    modeTrimShortSub = "Keyframe · Exact",
    modeRemuxTitle = "Change Format",
    modeRemuxShortTitle = "Change Format",
    modeRemuxSub = "100% lossless MP4 container remux (-c copy)",
    modeExtractAudioTitle = "Extract Audio",
    modeExtractAudioSub = "Original (Lossless) / AAC / MP3",
    modeAudioOnlyTitle = "Re-encode Audio Only",
    modeAudioOnlySub = "Video stream copy · Audio to AAC",
    modeVideoOnlyTitle = "Re-encode Video Only",
    modeVideoOnlySub = "CRF 16/20 · Audio stream copy",
    modeVideoAndAudioTitle = "Re-encode Video + Audio",
    modeVideoAndAudioSub = "CRF 16/20 · 8/10-bit · AAC",
    modeCustomCmdTitle = "Custom FFmpeg Command",
    modeCustomCmdSub = "Paste full CLI command (supports && chains)",
    badgeLossless = "Lossless",
    badgeLossy = "Lossy",

    codecSectionTitle = "Video Codec & Bit Depth",
    codecSectionSub = { codec, bitDepth -> "Source: $codec · $bitDepth · H.265 outputs automatically include -tag:v hvc1" },
    colorBitDepthLabel = "Color Bit Depth",
    bit8Compatible = "8-bit (Compatible)",
    bit8Default = "8-bit (Default)",
    bit10Preserve = "10-bit (Preserve)",
    nonNativeTenBitWarning = { src -> "Warning: Source video is $src (not 10-bit). Encoding an 8-bit source as 10-bit adds no real color depth, slows down encoding, and may reduce device compatibility." },
    fpsResSectionTitle = "Frame Rate & Smart Scaling",
    fpsResSectionSub = "3-way aspect preservation: Portrait (9:16 / 4:5), CinemaScope (2.39:1), and 16:9 auto-detected",
    recommend30Fps720pText = { vkbps -> "Bitrate drops too low for this video length (${vkbps}k). 30 FPS and 720p are recommended for cleaner visual quality." },
    fpsOriginalLabel = "Original FPS",
    fpsKeepSub = "Keep",
    targetResolutionLabel = "Target Resolution",
    resOriginalLabel = "Original",
    activeFilterPrefix = "Active Filter:",
    presetSectionTitle = "Encoding Speed (Preset)",
    presetCrfSub = { rec -> "Recommended: $rec · In CRF encoding, slower presets produce smaller files while keeping the exact same visual quality." },
    presetTargetSizeSub = { rec -> "Recommended: $rec" },
    presetTargetSizeHighlight = "Slower presets improve visual quality; because they compress more efficiently into smaller sizes, they deliver higher compression quality at your target MB.",
    presetVerySlowNote = "Note: The real-world quality/efficiency difference between slow and veryslow is only ~2–4%, while encoding takes roughly 2–3× longer.",
    audioBitrateTitle = "Audio Bitrate (AAC · Lossy)",
    audioBitrateExtractTitle = { fmt -> "Output Audio Bitrate ($fmt)" },
    audioBitrateSub = { kbps -> "Supports 32k – 768k (including 320k/384k+ for 5.1 surround) · Active: ${kbps}k" },
    customBitrateLabel = "Custom Bitrate (32–768 kbps)",
    outputFileNameHeader = "OUTPUT FILENAME",
    ffmpegCommandPreview = "FFmpeg Command Preview",
    actionHide = "Hide",
    actionShow = "Show",

    twoPassTitle = "True 2-Pass Target Size Calculation",
    twoPassSub = "Applies a 4% muxing overhead margin (MB × 8000 × 0.96) to prevent container overflow",
    targetMbInputLabel = "Target Size (MB) · e.g. 1, 8, 25",
    manualDurationLabel = "Duration unavailable · Enter in seconds",
    secondsUnitShort = "s",
    twoPassSummaryLine = { mb, codec, bit, vkbps, abps, preset ->
        "Target: < $mb MB  |  ${codec}${bit} ${vkbps}k  |  Audio: ${abps}k  |  $preset"
    },
    lowBitrateWarning = "Note: Video bitrate is very low for this target/audio budget; output may slightly exceed the target size.",

    previewStartTag = "START",
    previewEndTag = "END",
    previewFrameCd = { tag, time -> "$tag frame ($time)" },
    previewFrameFallbackCd = { tag -> "$tag frame" },
    playVideoButton = "Play Video",
    trimStartLabel = "Start",
    trimEndLabel = "End",
    trimStartPlaceholder = "00:00 / sec (e.g. 15)",
    trimStartSummaryDefault = "00:00",
    trimEndPlaceholder = "End",
    trimEndExceedsDurationWarning = { dur -> "End time exceeds video duration ($dur)!" },
    trimEndOutOfBoundsBadge = "OUT OF RANGE",
    trimStrategyLosslessTitle = "Lossless Cut",
    trimStrategyLosslessSub = "Keyframes · Original size",
    trimStrategyPreciseTitle = "Exact Cut",
    trimStrategyPreciseSub = "Frame-accurate · CRF encode",
    trimLosslessInfo = "Cuts losslessly on keyframe boundaries without re-encoding; video stays 100% original and file size scales proportionally with the trimmed duration.",
    trimPreciseInfo = "Re-encodes the video at your exact timestamps, which may noticeably increase file size. Encoded at CRF 16 (CRF 20 for HEVC) for virtually indistinguishable visual quality.",
    scanningKeyframes = "Scanning keyframes...",
    showKeyframesButton = "Show Keyframe Points",
    startKeyframesLabel = "Start Keyframes",
    endKeyframesLabel = "End Keyframes",
    noNearbyKeyframes = "No nearby keyframes found",
    selectedKeyframeCd = "Selected keyframe",
    trimModeTitle = "Trim Mode",
    trimModeSub = "Lossless cuts use -noaccurate_seek + -avoid_negative_ts make_zero",
    trimModeDisabledCd = "Unavailable in Exact Cut mode",
    trimSubFullLosslessTitle = "Fully Lossless",
    trimSubFullLosslessSub = "Video + Audio stream copy (-noaccurate_seek)",
    trimSubVideoLosslessAudioAacTitle = "Lossless Video + Convert Audio to AAC",
    trimSubVideoLosslessAudioAacSub = "Compatible trim for 5.1 / DTS / EAC3 audio",
    trimSubCrfAudioCopyTitle = "Re-encode Video (CRF) + Lossless Audio",
    trimSubCrfAudioCopySub = "Frame-accurate cut · 100% lossless audio copy",
    trimSubCrfAudioAacTitle = "Re-encode Video + Audio (CRF + AAC)",
    trimSubCrfAudioAacSub = "Frame-accurate cut · Full re-encode",

    remuxCardTitle = "100% Lossless MP4 Remux",
    remuxCardSub = "Rewraps streams into an MP4 container without re-encoding (-c copy), moves the moov atom to the front (+faststart), and strips incompatible subtitle/data tracks (-sn -dn).",
    remuxCardHighlight = "Completes in seconds · Zero quality loss",
    audioOnlyCardTitle = "100% Lossless Video Stream Copy",
    audioOnlyCardSub = { codec -> "Only the audio track ($codec) is transcoded to AAC at the selected bitrate (-c:v copy)." },
    audioOnlyCardBody = "Ideal for making Opus, DTS, AC3, or EAC3 videos fully compatible with mobile players, WhatsApp, and standard MP4 playback.",
    videoOnlyAudioTitle = "Audio Stream",
    videoOnlyAudioCopySub = { codec -> "Source audio ($codec) is copied losslessly without re-encoding (-c:a copy)." },
    videoOnlyNoAudioSub = "No audio track in source video (-an).",
    videoOnlyCrfBody = "Applies CRF 16 for H.264 or equivalent archival-grade CRF 20 for H.265.",
    audioExtractTitle = "Audio Extraction Format",
    audioExtractSub = "If the source audio is already AAC or MP3, it is stream-copied losslessly without re-encoding",
    audioExtractOriginalOption = { codec, ext -> "Original ($codec → .$ext)" },
    audioExtractOriginalLabel = "Original Codec",
    customCmdTitle = "Custom FFmpeg Command (Full CLI)",
    customCmdSub = "Supports multi-step && command chains · Generated outputs are automatically saved to Movies/FFmpeg",
    customCmdShortcutsLabel = "Input placeholders (tap to insert):",
    customCmdQuickRemuxChip = "Quick Remux",
    customCmdDefaultOutput = "output.mp4",
    customCmdInputLabel = "FFmpeg Command",

    passBadge = { p, t -> "PASS $p/$t" },
    processingBadge = "PROCESSING",
    formatPercent = { pct -> "$pct%" },
    cpuBoostWithFreq = { ghz, cores -> "Prime ($ghz) priority · $cores cores full power" },
    cpuBoostGeneric = { cores -> "Prime core priority · $cores cores full power" },
    wakeLockActiveSuffix = "Wake lock active.",
    enableMagicCapsuleButton = "Enable Magic Capsule / Live Updates",
    successCd = "Success",
    successTitle = "Processing Complete",
    successMediaStoreNote = "MediaStore updated immediately for Gallery and Recent Files.",
    smallerPercentBadge = { pct -> "$pct% smaller" },
    failedCancelledTitle = "Task Cancelled",
    failedErrorTitle = "Encoding Failed",
    logConsoleHeader = { count -> "Status & Error Log ($count)" },
    logConsoleEmpty = "Startup status, pass transitions, and any errors will appear here.",

    errAnalyzeFailed = { detail -> "Failed to analyze file: $detail" },
    errSelectMediaFirst = "Select a video or media file above first.",
    errNoVideoTrack = "No video stream found in this file. Use Extract Audio or Custom Command.",
    errNoAudioTrack = "No audio track found in this file.",
    errEnterTrimTimeFirst = "Enter a start or end timestamp first.",
    errInvalidStartTime = "Invalid start timestamp (e.g., 00:01:00 or 01:30).",
    errInvalidEndTime = "Invalid end timestamp (e.g., 00:02:32 or 02:32).",
    errEndBeforeStart = "Error: End time must be greater than start time.",
    errKeyframeRequiresVideo = "No video stream in this file; keyframe scanning requires video.",
    errKeyframeScanFailed = { msg -> "Keyframe scan failed: $msg" },
    errInvalidTargetMb = "Enter a valid target size in MB (e.g., 1, 8, 25).",
    errEnterManualDuration = "Could not detect video duration. Please enter duration in seconds.",
    errNoTrimRangeEntered = "Please enter a start or end time.",
    errInvalidTimeFormat = "Invalid timestamp format (e.g., 01:30 or 00:01:30).",
    errEnterCustomCommand = "Enter an FFmpeg command to run.",

    notifChannelName = "Media Processing",
    notifChannelDesc = "FFmpeg transcoding and trimming progress notification",
    notifPreparing = "Preparing...",
    logTagStarted = "[Started]",
    logTagStep = { p, t -> "[Step $p/$t]" },
    logTagError = "[Error]",
    logTagCompleted = "[Completed]",
    errFfmpegExitCode = { code -> "FFmpeg exited with an error (code: $code)." },
    errOutputNotCreated = "Output file was not created.",
    errUnexpected = "An unexpected error occurred.",
    successOutputSize = { size -> "Done! Output size: $size" },
    errCustomCmdEmpty = "No valid FFmpeg command entered.",
    hintCustomCmdExample = "Example: ffmpeg -i \"\$IN\" -c:v libx264 -crf 20 output.mp4",
    customCmdStepMulti = { p, t -> "Running command $p/$t" },
    customCmdStepSingle = "Running custom FFmpeg command",
    customCmdOutputLabel = "Custom Command",
    errCustomCmdFailed = { code -> "Custom FFmpeg command failed (code: $code)." },
    hintCustomCmdSyntax = { ext -> "Check command syntax and input placeholders (\"\$IN\" or input.$ext)." },
    successCustomCmdDoneNoFiles = "Done! Command completed.",
    successCustomCmdSavedFiles = { count, total ->
        if (count == 1) "Done! Saved 1 file ($total)" else "Done! Saved $count files ($total)"
    },
    bannerTrimRange = { s, e, sub -> "Range: $s → $e | $sub" },
    bannerRemux = "Lossless MP4 container remux (-c copy +faststart)",
    bannerAudioOnly = { ab -> "Video stream copy | Audio: ${ab}k AAC" },
    bannerVideoOnly = { codec, bit, crf, preset -> "${codec}${bit} CRF $crf ($preset) | Audio copy" },
    bannerSilentAudio = "Silent",
    bannerExtractAudio = { fmt -> "Extract Audio · $fmt" },
    bannerCustomCmd = "Custom FFmpeg Command (Outputs saved automatically)",
    hintTrimLosslessAudio = "Tip: If the audio codec isn't MP4-compatible (e.g., Opus/DTS/EAC3), select trim mode 2 (Lossless Video + Convert Audio to AAC).",
    hintVideoOnlyAudio = "Tip: If the audio codec isn't MP4-compatible, use 10 · Re-encode Video + Audio instead.",
    hintRemuxCompat = "Tip: If the video or audio codec is incompatible with MP4, try 08 · Re-encode Audio Only or 10 · Re-encode Video + Audio.",
    errSafReadFailed = "Unable to open file for reading (SAF)",
    errUnknown = "Unknown error",
    errMediaStoreInsertFailed = "Could not create MediaStore entry",
    errOutputStreamFailed = "Could not open output stream",
    errSaveFailed = { detail -> "Failed to save file: $detail" },
    errUnknownMediaStore = "Unknown MediaStore error",

    telemetryPrefix = "Time",
    stepPass1 = "Pass 1/2 (Analyzing)",
    stepPass2 = "Pass 2/2 (Encoding)",
    stepTrimLossless = "Trimming losslessly (-noaccurate_seek)",
    stepTrimVideoLosslessAudioAac = "Trimming (Lossless Video + AAC Audio)",
    stepTrimCrfAudioCopy = { codec, bit, crf, preset -> "Trimming ${codec}${bit} CRF $crf ($preset | audio copy)" },
    stepTrimCrfAudioAac = { codec, bit, crf, preset -> "Trimming ${codec}${bit} CRF $crf ($preset) + AAC" },
    stepRemux = "Remuxing container (-c copy)",
    stepAudioOnly = { ab -> "Video copy · Audio ${ab}k AAC" },
    stepVideoOnly = { codec, bit, crf, preset -> "Video ${codec}${bit} CRF $crf ($preset | audio copy)" },
    stepExtractOriginal = { codec, ext -> "Original audio (100% lossless: $codec → .$ext)" },
    stepExtractAacCopy = "Audio is already AAC · Lossless stream copy",
    stepExtractAacTranscode = { ab -> "Extracting audio as ${ab}k AAC" },
    stepExtractMp3Copy = "Audio is already MP3 · Lossless stream copy",
    stepExtractMp3Transcode = { ab -> "Extracting audio as ${ab}k MP3" },
    customCommandOutputPlaceholder = "Defined by command",
    fileTagClip = "Clip",
    fileTagSilent = "Mute",
    fileTagOriginal = "Original",
    fileTagAudio = "Audio"
)

// Localized display helpers for domain enums

fun ModeCategory.localizedTitle(s: AppStrings): String = when (this) {
    ModeCategory.TARGET_SIZE -> s.catTargetSize
    ModeCategory.TRIM -> s.catTrim
    ModeCategory.OTHER -> s.catOther
}

fun ProcessingMode.localizedTitle(s: AppStrings): String = when (this) {
    ProcessingMode.DISCORD_20MB -> "Discord"
    ProcessingMode.WHATSAPP_100MB -> "WhatsApp"
    ProcessingMode.GMAIL_25MB -> "Gmail"
    ProcessingMode.CUSTOM_SIZE -> s.modeCustomSizeTitle
    ProcessingMode.TRIM_VIDEO -> s.modeTrimTitle
    ProcessingMode.REMUX_FORMAT -> s.modeRemuxTitle
    ProcessingMode.EXTRACT_AUDIO -> s.modeExtractAudioTitle
    ProcessingMode.AUDIO_ONLY_AAC -> s.modeAudioOnlyTitle
    ProcessingMode.VIDEO_ONLY_CRF -> s.modeVideoOnlyTitle
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> s.modeVideoAndAudioTitle
    ProcessingMode.CUSTOM_COMMAND -> s.modeCustomCmdTitle
}

fun ProcessingMode.localizedShortTitle(s: AppStrings): String = when (this) {
    ProcessingMode.DISCORD_20MB -> "Discord"
    ProcessingMode.WHATSAPP_100MB -> "WhatsApp"
    ProcessingMode.GMAIL_25MB -> "Gmail"
    ProcessingMode.CUSTOM_SIZE -> s.modeCustomSizeTitle
    ProcessingMode.TRIM_VIDEO -> s.modeTrimTitle
    ProcessingMode.REMUX_FORMAT -> s.modeRemuxShortTitle
    ProcessingMode.EXTRACT_AUDIO -> s.modeExtractAudioTitle
    ProcessingMode.AUDIO_ONLY_AAC -> s.modeAudioOnlyTitle
    ProcessingMode.VIDEO_ONLY_CRF -> s.modeVideoOnlyTitle
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> s.modeVideoAndAudioTitle
    ProcessingMode.CUSTOM_COMMAND -> s.modeCustomCmdTitle
}

fun ProcessingMode.localizedSubtitle(s: AppStrings): String = when (this) {
    ProcessingMode.DISCORD_20MB -> s.modeDiscordSub
    ProcessingMode.WHATSAPP_100MB -> s.modeWhatsappSub
    ProcessingMode.GMAIL_25MB -> s.modeGmailSub
    ProcessingMode.CUSTOM_SIZE -> s.modeCustomSizeSub
    ProcessingMode.TRIM_VIDEO -> s.modeTrimSub
    ProcessingMode.REMUX_FORMAT -> s.modeRemuxSub
    ProcessingMode.EXTRACT_AUDIO -> s.modeExtractAudioSub
    ProcessingMode.AUDIO_ONLY_AAC -> s.modeAudioOnlySub
    ProcessingMode.VIDEO_ONLY_CRF -> s.modeVideoOnlySub
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> s.modeVideoAndAudioSub
    ProcessingMode.CUSTOM_COMMAND -> s.modeCustomCmdSub
}

fun ProcessingMode.localizedShortSubtitle(s: AppStrings): String = when (this) {
    ProcessingMode.DISCORD_20MB -> "< 20 MB"
    ProcessingMode.WHATSAPP_100MB -> "< 100 MB"
    ProcessingMode.GMAIL_25MB -> "< 25 MB"
    ProcessingMode.CUSTOM_SIZE -> s.modeCustomSizeShortSub
    ProcessingMode.TRIM_VIDEO -> s.modeTrimShortSub
    ProcessingMode.REMUX_FORMAT -> s.badgeLossless
    ProcessingMode.EXTRACT_AUDIO -> s.badgeLossless
    ProcessingMode.AUDIO_ONLY_AAC -> s.badgeLossless
    ProcessingMode.VIDEO_ONLY_CRF -> s.badgeLossy
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> s.badgeLossy
    ProcessingMode.CUSTOM_COMMAND -> ""
}

fun ProcessingMode.localizedQualityTag(s: AppStrings): String = when (this) {
    ProcessingMode.DISCORD_20MB,
    ProcessingMode.WHATSAPP_100MB,
    ProcessingMode.GMAIL_25MB,
    ProcessingMode.CUSTOM_SIZE -> "2-Pass"
    ProcessingMode.TRIM_VIDEO,
    ProcessingMode.REMUX_FORMAT,
    ProcessingMode.EXTRACT_AUDIO,
    ProcessingMode.AUDIO_ONLY_AAC -> s.badgeLossless
    ProcessingMode.VIDEO_ONLY_CRF,
    ProcessingMode.VIDEO_AND_AUDIO_CRF -> s.badgeLossy
    ProcessingMode.CUSTOM_COMMAND -> ""
}

fun FpsOption.localizedLabel(s: AppStrings): String = when (this) {
    FpsOption.ORIGINAL -> s.fpsOriginalLabel
    FpsOption.FPS_30 -> "30 FPS"
}

fun ResolutionOption.localizedLabel(s: AppStrings): String = when (this) {
    ResolutionOption.ORIGINAL -> s.resOriginalLabel
    else -> label
}

fun TrimCutStrategy.localizedTitle(s: AppStrings): String = when (this) {
    TrimCutStrategy.LOSSLESS_KEYFRAME -> s.trimStrategyLosslessTitle
    TrimCutStrategy.PRECISE_REENCODE -> s.trimStrategyPreciseTitle
}

fun TrimCutStrategy.localizedSubtitle(s: AppStrings): String = when (this) {
    TrimCutStrategy.LOSSLESS_KEYFRAME -> s.trimStrategyLosslessSub
    TrimCutStrategy.PRECISE_REENCODE -> s.trimStrategyPreciseSub
}

fun TrimSubMode.localizedTitle(s: AppStrings): String = when (this) {
    TrimSubMode.FULL_LOSSLESS -> s.trimSubFullLosslessTitle
    TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC -> s.trimSubVideoLosslessAudioAacTitle
    TrimSubMode.CRF_AUDIO_COPY -> s.trimSubCrfAudioCopyTitle
    TrimSubMode.CRF_AUDIO_AAC -> s.trimSubCrfAudioAacTitle
}

fun TrimSubMode.localizedSubtitle(s: AppStrings): String = when (this) {
    TrimSubMode.FULL_LOSSLESS -> s.trimSubFullLosslessSub
    TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC -> s.trimSubVideoLosslessAudioAacSub
    TrimSubMode.CRF_AUDIO_COPY -> s.trimSubCrfAudioCopySub
    TrimSubMode.CRF_AUDIO_AAC -> s.trimSubCrfAudioAacSub
}

fun AudioExtractFormat.localizedLabel(s: AppStrings): String = when (this) {
    AudioExtractFormat.ORIGINAL -> s.audioExtractOriginalLabel
    AudioExtractFormat.AAC -> "AAC (.m4a)"
    AudioExtractFormat.MP3 -> "MP3 (.mp3)"
}
