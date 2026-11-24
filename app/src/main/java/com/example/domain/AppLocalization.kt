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

/**
 * Interface-backed string table (avoids the 255-parameter JVM/DEX constructor limit
 * that caused ART VerifyError on large data classes).
 */
interface AppStrings {
    val language: AppLanguage
    // Top bar & language menu
    val languageSelectorCd: String
    val languageAutoSystem: String
    val historyTitle: String
    val historyClear: String
    val historyClearCd: String
    val historyEmpty: String
    val batteryOptDialogTitle: String
    val batteryOptDialogBody: String
    val batteryOptAllowButton: String
    val batteryOptLaterButton: String
    val creditsInfoCd: String
    val creditsDialogTitle: String
    val creditsDialogSubtitle: String
    val creditsCoreSectionLabel: String
    val creditsCoreCreators: String
    val creditsCoreEncoders: String
    val creditsOriginalLibSectionLabel: String
    val creditsOriginalLibCreator: String
    val creditsForkSectionLabel: String
    val creditsForkMaintainer: String
    val creditsMedia3SectionLabel: String
    val creditsMedia3Author: String
    val actionClose: String

    // Common actions & intent choosers
    val actionBack: String
    val actionCancel: String
    val actionStart: String
    val actionOpen: String
    val actionShare: String
    val actionNewTask: String
    val actionHome: String
    val actionBackToSettings: String
    val toastNoAppToOpenFile: String
    val toastShareFailed: String
    val chooserPlayVideoWith: String
    val toastNoAppToPlayVideo: String

    // Media hero card
    val analyzingMedia: String
    val selectSourceMedia: String
    val pickGalleryVideo: String
    val pickFile: String
    val changeFile: String
    val changeFileCd: String
    val audioOnlyTag: String
    val audioTrackPrefix: String
    val noneLabel: String
    val evenDimensionFixChip: String

    // Home screen badges & categories
    val noVideoBadge: String
    val noAudioBadge: String
    val catTargetSize: String
    val catTrim: String
    val catOther: String

    // Mode names, subtitles & badges
    val modeCustomSizeTitle: String
    val modeCustomSizeShortSub: String
    val modeCustomSizeSub: String
    val modeDiscordSub: String
    val modeWhatsappSub: String
    val modeGmailSub: String
    val modeTrimTitle: String
    val modeTrimSub: String
    val modeTrimShortSub: String
    val modeRemuxTitle: String
    val modeRemuxShortTitle: String
    val modeRemuxSub: String
    val modeExtractAudioTitle: String
    val modeExtractAudioSub: String
    val modeAudioOnlyTitle: String
    val modeAudioOnlySub: String
    val modeVideoOnlyTitle: String
    val modeVideoOnlySub: String
    val modeVideoAndAudioTitle: String
    val modeVideoAndAudioSub: String
    val modeCustomCmdTitle: String
    val modeCustomCmdSub: String
    val badgeLossless: String
    val badgeLossy: String

    // Encoder controls
    val codecSectionTitle: String
    val codecSectionSub: (codec: String, bitDepth: String) -> String
    val colorBitDepthLabel: String
    val bit8Compatible: String
    val bit8Default: String
    val bit10Preserve: String
    val nonNativeTenBitWarning: (sourceBitDepth: String) -> String
    val fpsResSectionTitle: String
    val fpsResSectionSub: String
    val recommend30Fps720pText: (vkbps: Int) -> String
    val fpsOriginalLabel: String
    val fpsKeepSub: String
    val targetResolutionLabel: String
    val resOriginalLabel: String
    val activeFilterPrefix: String
    val presetSectionTitle: String
    val presetCrfSub: (recommended: String) -> String
    val presetTargetSizeSub: (recommended: String) -> String
    val presetTargetSizeHighlight: String
    val presetVerySlowNote: String
    val audioBitrateTitle: String
    val audioBitrateExtractTitle: (format: String) -> String
    val audioBitrateSub: (activeKbps: Int) -> String
    val audioBitrateMp3Sub: (activeKbps: Int) -> String
    val audioBitrateSourceCapSub: (sourceKbps: Int, activeKbps: Int) -> String
    val audioBitrateUpscaleWarning: (sourceKbps: Int, codec: String, requestedKbps: Int) -> String
    val customBitrateLabel: String
    val customBitrateMp3Label: String
    val cancelConfirmTitle: String
    val cancelConfirmBody: String
    val cancelConfirmYes: String
    val cancelConfirmKeepRunning: String
    val errUnsupportedMediaFile: String
    val errStartExceedsDuration: (durText: String) -> String
    val errEncodingInProgress: String
    val outputFileNameHeader: String
    val ffmpegCommandPreview: String
    val actionHide: String
    val actionShow: String

    // Two-pass target card
    val twoPassTitle: String
    val twoPassSub: String
    val targetMbInputLabel: String
    val manualDurationLabel: String
    val secondsUnitShort: String
    val twoPassSummaryLine: (mbTag: String, codecTag: String, bitTag: String, vkbps: Int, abps: Int, preset: String) -> String
    val lowBitrateWarning: String

    // Trim screen
    val previewStartTag: String
    val previewEndTag: String
    val previewFrameCd: (tag: String, time: String) -> String
    val previewFrameFallbackCd: (tag: String) -> String
    val playVideoButton: String
    val trimStartLabel: String
    val trimEndLabel: String
    val trimStartPlaceholder: String
    val trimStartSummaryDefault: String
    val trimEndPlaceholder: String
    val trimEndExceedsDurationWarning: (durText: String) -> String
    val trimEndOutOfBoundsBadge: String
    val trimStrategyLosslessTitle: String
    val trimStrategyLosslessSub: String
    val trimStrategyPreciseTitle: String
    val trimStrategyPreciseSub: String
    val trimLosslessInfo: String
    val trimPreciseInfo: String
    val scanningKeyframes: String
    val showKeyframesButton: String
    val startKeyframesLabel: String
    val endKeyframesLabel: String
    val noNearbyKeyframes: String
    val selectedKeyframeCd: String
    val trimModeTitle: String
    val trimModeSub: String
    val trimModeDisabledCd: String
    val trimSubFullLosslessTitle: String
    val trimSubFullLosslessSub: String
    val trimSubVideoLosslessAudioAacTitle: String
    val trimSubVideoLosslessAudioAacSub: String
    val trimSubCrfAudioCopyTitle: String
    val trimSubCrfAudioCopySub: String
    val trimSubCrfAudioAacTitle: String
    val trimSubCrfAudioAacSub: String

    // Remux / Audio-Only / Video-Only / Audio Extract / Custom Command cards
    val remuxCardTitle: String
    val remuxCardSub: String
    val remuxCardHighlight: String
    val audioOnlyCardTitle: String
    val audioOnlyCardSub: (codec: String) -> String
    val audioOnlyCardBody: String
    val videoOnlyAudioTitle: String
    val videoOnlyAudioCopySub: (codec: String) -> String
    val videoOnlyNoAudioSub: String
    val videoOnlyCrfBody: String
    val audioExtractTitle: String
    val audioExtractSub: String
    val audioExtractOriginalOption: (codec: String, ext: String) -> String
    val audioExtractOriginalLabel: String
    val customCmdTitle: String
    val customCmdSub: String
    val customCmdAiTip: String
    val customCmdPasteButton: String
    val customCmdClearButton: String
    val customCmdShortcutsLabel: String
    val customCmdQuickRemuxChip: String
    val customCmdQuickTwoPassChip: String
    val customCmdQuickGifChip: String
    val customCmdQuickMuteChip: String
    val customCmdDefaultOutput: String
    val customCmdInputLabel: String
    val customCmdPlaceholder: String
    val customCmdStepsDetected: (count: Int) -> String
    val customCmdBadgeInputAutoBound: String
    val customCmdBadgeTwoPassReady: String
    val customCmdBadgeMarkdownCleaned: String
    val customCmdBadgeWindowsNullFixed: String
    val customCmdBadgeOutputAutoAdded: String
    val customCmdBadgeSyntaxFixed: String
    val customCmdBadgeAndroidCodecAdapted: String
    val customCmdNoValidCommandWarning: String

    // Processing screen
    val passBadge: (passIndex: Int, totalPasses: Int) -> String
    val processingBadge: String
    val formatPercent: (percent: Int) -> String
    val cpuBoostWithFreq: (ghz: String, cores: Int) -> String
    val cpuBoostGeneric: (cores: Int) -> String
    val wakeLockActiveSuffix: String
    val enableMagicCapsuleButton: String
    val successCd: String
    val successTitle: String
    val successMediaStoreNote: String
    val smallerPercentBadge: (percent: Int) -> String
    val failedCancelledTitle: String
    val failedErrorTitle: String
    val logConsoleHeader: (count: Int) -> String
    val logConsoleEmpty: String

    // Validation & ViewModel errors
    val errAnalyzeFailed: (detail: String) -> String
    val errSelectMediaFirst: String
    val errNoVideoTrack: String
    val errNoAudioTrack: String
    val errEnterTrimTimeFirst: String
    val errInvalidStartTime: String
    val errInvalidEndTime: String
    val errEndBeforeStart: String
    val errKeyframeRequiresVideo: String
    val errKeyframeScanFailed: (msg: String) -> String
    val errInvalidTargetMb: String
    val errEnterManualDuration: String
    val errNoTrimRangeEntered: String
    val errInvalidTimeFormat: String
    val errEnterCustomCommand: String

    // Foreground service, logs, banners, hints & engine errors
    val notifChannelName: String
    val notifChannelDesc: String
    val notifPreparing: String
    val logTagStarted: String
    val logTagStep: (passNum: Int, totalPasses: Int) -> String
    val logTagError: String
    val logTagCompleted: String
    val errFfmpegExitCode: (code: Int) -> String
    val errOutputNotCreated: String
    val errUnexpected: String
    val successOutputSize: (size: String) -> String
    val errCustomCmdEmpty: String
    val hintCustomCmdExample: String
    val customCmdStepMulti: (passNum: Int, totalSteps: Int) -> String
    val customCmdStepSingle: String
    val customCmdOutputLabel: String
    val errCustomCmdFailed: (code: Int) -> String
    val hintCustomCmdSyntax: (ext: String) -> String
    val successCustomCmdDoneNoFiles: String
    val successCustomCmdSavedFiles: (count: Int, totalSize: String) -> String
    val bannerTrimRange: (start: String, end: String, subMode: String) -> String
    val bannerRemux: String
    val bannerAudioOnly: (ab: Int) -> String
    val bannerVideoOnly: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String
    val bannerSilentAudio: String
    val bannerExtractAudio: (formatLabel: String) -> String
    val bannerCustomCmd: String
    val hintTrimLosslessAudio: String
    val hintVideoOnlyAudio: String
    val hintRemuxCompat: String
    val errSafReadFailed: String
    val errUnknown: String
    val errMediaStoreInsertFailed: String
    val errOutputStreamFailed: String
    val errSaveFailed: (detail: String) -> String
    val errUnknownMediaStore: String

    // FfmpegMath step labels, telemetry & filename suffixes
    val telemetryPrefix: String
    val stepPass1: String
    val stepPass2: String
    val stepTrimLossless: String
    val stepTrimVideoLosslessAudioAac: String
    val stepTrimCrfAudioCopy: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String
    val stepTrimCrfAudioAac: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String
    val stepRemux: String
    val stepAudioOnly: (ab: Int) -> String
    val stepVideoOnly: (codecTag: String, bitTag: String, crf: Int, preset: String) -> String
    val stepExtractOriginal: (codec: String, ext: String) -> String
    val stepExtractAacCopy: String
    val stepExtractAacTranscode: (ab: Int) -> String
    val stepExtractMp3Copy: String
    val stepExtractMp3Transcode: (ab: Int) -> String
    val customCommandOutputPlaceholder: String
    val fileTagClip: String
    val fileTagSilent: String
    val fileTagOriginal: String
    val fileTagAudio: String

    // Media3 Hardware Turbo ("Acelem Var") mode
    val actionInAHurry: String
    val turboButtonSubBadge: String
    val turboConfirmTitle: String
    val turboConfirmBody: String
    val turboConfirmQuestion: String
    val turboConfirmYes: String
    val turboConfirmNo: String
    val turboScreenTitle: String
    val turboGaugeSubLabel: String
    val turboHardwareChip: String
    val turboStatusPreparing: String
    val turboStatusEncoding: String
    val turboStatusSaving: String
    val turboElapsedLabel: (formatted: String) -> String
    val turboSuccessBadge: String
    val turboUnsupportedTitle: String
    val turboUnsupportedBody: String
    val turboUnsupportedHint: String
    val turboErrorGenericHint: String
    val turboFallbackToFfmpegButton: String
    val turboFallbackFastFfmpegButton: String
    val turboNotifTitle: String
    val turboSettingsPreservedNote: String
    val turboRemuxPromptTitle: String
    val turboRemuxPromptBody: String
    val turboRemuxPromptYes: String
    val turboRemuxPromptNo: String
    val turboTryRemuxButton: String
    val turboRemuxModeTitle: String
    val turboRemuxStepStatus: String
    val turboAfterRemuxFailedTitle: String
    val turboAfterRemuxFailedBody: String
    val turboAfterRemuxFastHint: String
}

object TurkishStrings : AppStrings {
    override val language = AppLanguage.TR
    override val languageSelectorCd = "Dil seç"
    override val languageAutoSystem = "Sistem Dili (Otomatik)"
    override val historyTitle = "Son Çıktılar"
    override val historyClear = "Temizle"
    override val historyClearCd = "Geçmişi temizle"
    override val historyEmpty = "Henüz işlenmiş dosya yok. Bir video seçerek başlayın."
    override val batteryOptDialogTitle = "Pil Tasarrufunu Yoksay"
    override val batteryOptDialogBody = "FFmpeg uzun video kodlamalarında işlemci çekirdeklerini tam performansta kullanabilmek ve arka planda kısıtlanmamak için pil optimizasyonunun kapatılmasını önerir."
    override val batteryOptAllowButton = "İzin Ver"
    override val batteryOptLaterButton = "Sonra"
    override val creditsInfoCd = "Kütüphane ve emek sahipleri"
    override val creditsDialogTitle = "Açık Kaynak Emeği"
    override val creditsDialogSubtitle = "Bu uygulama aşağıdaki açık kaynak kütüphanelerin ve geliştiricilerinin emeği üzerine kuruludur."
    override val creditsCoreSectionLabel = "Çekirdek Medya Motoru"
    override val creditsCoreCreators = "Kurucu: Fabrice Bellard · Lider & Ekip: Michael Niedermayer ve FFmpeg Geliştiricileri"
    override val creditsCoreEncoders = "Dahili kodekler: x264 (VideoLAN), x265 (MulticoreWare), LAME MP3"
    override val creditsOriginalLibSectionLabel = "Orijinal Android Kütüphanesi"
    override val creditsOriginalLibCreator = "Yapımcı: Taner Şener (ARTHENICA)"
    override val creditsForkSectionLabel = "Kullanılan Özel Fork"
    override val creditsForkMaintainer = "Sürdürücü: Jokobee & ffmpegkit-maintained Topluluğu"
    override val creditsMedia3SectionLabel = "Donanım Hızlandırma Motoru"
    override val creditsMedia3Author = "Geliştirici: Google / Android Open Source Project (Apache 2.0)"
    override val actionClose = "Kapat"

    override val actionBack = "Geri"
    override val actionCancel = "İptal Et"
    override val actionStart = "İşlemi Başlat"
    override val actionOpen = "Oynat / Aç"
    override val actionShare = "Paylaş"
    override val actionNewTask = "Yeni İşlem"
    override val actionHome = "Ana Menü"
    override val actionBackToSettings = "Ayarlara Dön"
    override val toastNoAppToOpenFile = "Dosyayı açacak uygulama bulunamadı."
    override val toastShareFailed = "Paylaşım başlatılamadı."
    override val chooserPlayVideoWith = "Hangi uygulamada oynatılsın?"
    override val toastNoAppToPlayVideo = "Videoyu açacak uygulama bulunamadı."

    override val analyzingMedia = "Medya analiz ediliyor..."
    override val selectSourceMedia = "Kaynak Medya Seç"
    override val pickGalleryVideo = "Galeri (Video)"
    override val pickFile = "Dosya Seç"
    override val changeFile = "Değiştir"
    override val changeFileCd = "Dosyayı değiştir"
    override val audioOnlyTag = "SES"
    override val audioTrackPrefix = "Ses"
    override val noneLabel = "Yok"
    override val evenDimensionFixChip = "Tek Piksel Koruması"

    override val noVideoBadge = "Video yok"
    override val noAudioBadge = "Ses izi yok"
    override val catTargetSize = "Hedef Boyut"
    override val catTrim = "Videoyu Kes"
    override val catOther = "Daha Fazla"

    override val modeCustomSizeTitle = "Özel Boyut"
    override val modeCustomSizeShortSub = "MB Gir"
    override val modeCustomSizeSub = "İstediğin MB hedefi · 2-Pass"
    override val modeDiscordSub = "< 20 MB · Gerçek 2-Pass ABR"
    override val modeWhatsappSub = "< 100 MB · Gerçek 2-Pass ABR"
    override val modeGmailSub = "< 25 MB · Gerçek 2-Pass ABR"
    override val modeTrimTitle = "Videoyu Kes"
    override val modeTrimSub = "Akıllı Keyframe tarama · Kayıpsız / Hassas"
    override val modeTrimShortSub = "Keyframe · Hassas"
    override val modeRemuxTitle = "Sadece Format Değiştir"
    override val modeRemuxShortTitle = "Format Değiştir"
    override val modeRemuxSub = "%100 kayıpsız MP4 kutu yenileme (-c copy)"
    override val modeExtractAudioTitle = "Sesi Çıkart"
    override val modeExtractAudioSub = "Orijinal (Kayıpsız) / AAC / MP3"
    override val modeAudioOnlyTitle = "Sadece Ses Değiştir"
    override val modeAudioOnlySub = "Görüntü %100 copy · Ses AAC"
    override val modeVideoOnlyTitle = "Sadece Görüntü Değiştir"
    override val modeVideoOnlySub = "CRF 16/20 · Ses %100 copy"
    override val modeVideoAndAudioTitle = "Görüntü + Ses Değiştir"
    override val modeVideoAndAudioSub = "CRF 16/20 · 8/10-bit · AAC"
    override val modeCustomCmdTitle = "Özel FFmpeg Komutu"
    override val modeCustomCmdSub = "Tam komut yapıştır (&& zinciri dahil)"
    override val badgeLossless = "Kayıpsız"
    override val badgeLossy = "Kayıplı"

    override val codecSectionTitle = "Video Kodeği & Renk Derinliği"
    override val codecSectionSub: (String, String) -> String = { codec, bitDepth -> "Girdi: $codec · $bitDepth · H.265 çıktılar otomatik -tag:v hvc1 alır" }
    override val colorBitDepthLabel = "Renk Bit Derinliği"
    override val bit8Compatible = "8-bit (Uyumlu)"
    override val bit8Default = "8-bit (Varsayılan)"
    override val bit10Preserve = "10-bit (Koru)"
    override val nonNativeTenBitWarning: (String) -> String = { src -> "Uyarı: Orijinal kaynak video $src (10-bit değil). 8-bit kaynaktan 10-bit kodlamak ekstra renk derinliği katmaz, kodlama süresini uzatır ve bazı cihazlarda uyumluluğu düşürebilir." }
    override val fpsResSectionTitle = "FPS & Akıllı Çözünürlük"
    override val fpsResSectionSub = "3 yönlü oran koruması: Dikey (9:16 / 4:5), Sinema (2.39:1) ve 16:9 otomatik algılanır"
    override val recommend30Fps720pText: (Int) -> String = { vkbps -> "Video uzunluğuna göre bitrate aşırı düşüyor (${vkbps}k). Daha net görüntü için 30 FPS ve 720p tavsiye edilir." }
    override val fpsOriginalLabel = "Orijinal FPS"
    override val fpsKeepSub = "Koru"
    override val targetResolutionLabel = "Hedef Çözünürlük"
    override val resOriginalLabel = "Orijinal"
    override val activeFilterPrefix = "Aktif Filtre:"
    override val presetSectionTitle = "Kodlama Hızı (Preset)"
    override val presetCrfSub: (String) -> String = { rec -> "Önerilen: $rec · CRF dönüşümünde daha yavaş preset daha küçük dosya boyutu üretir, kalite değişmez." }
    override val presetTargetSizeSub: (String) -> String = { rec -> "Önerilen: $rec" }
    override val presetTargetSizeHighlight = "Daha yavaş preset görüntü kalitesini artırır; daha küçük dosya boyutu üreteceği için sıkıştırma kalitesini artırır."
    override val presetVerySlowNote = "Not: slow ile veryslow arasındaki gerçek kalite/verimlilik farkı yalnızca ~%2–4 civarındadır; ancak kodlama süresi yaklaşık 2–3 kat uzar."
    override val audioBitrateTitle = "Ses Bitrate (AAC · Kayıplı)"
    override val audioBitrateExtractTitle: (String) -> String = { fmt -> "Çıktı Ses Bitrate ($fmt)" }
    override val audioBitrateSub: (Int) -> String = { kbps -> "32k – 768k aralığı desteklenir (5.1 surround için 320k/384k+ dahil) · Aktif: ${kbps}k" }
    override val audioBitrateMp3Sub: (Int) -> String = { kbps -> "MP3 için 32k – 320k aralığı desteklenir · Aktif: ${kbps}k" }
    override val audioBitrateSourceCapSub: (Int, Int) -> String = { src, kbps -> "Kaynak ses: ${src}k · Hızlı seçenekler kaynak kalitesine göre ayarlandı · Aktif: ${kbps}k" }
    override val audioBitrateUpscaleWarning: (Int, String, Int) -> String = { src, codec, req -> "Uyarı: Kaynağın sesi ${src}k ($codec) seviyesinde. Bundan yüksek bitrate (${req}k) girmek ses kalitesini artırmaz, yalnızca dosya boyutunu gereksiz büyütür." }
    override val customBitrateLabel = "Özel Bitrate (32..768 kbps)"
    override val customBitrateMp3Label = "Özel Bitrate (32..320 kbps)"
    override val cancelConfirmTitle = "İşlemi İptal Et?"
    override val cancelConfirmBody = "Devam eden işlem durdurulacak ve şu ana kadarki ilerleme silinecek. Emin misiniz?"
    override val cancelConfirmYes = "Evet, İptal Et"
    override val cancelConfirmKeepRunning = "Devam Et"
    override val errUnsupportedMediaFile = "Bu dosya geçerli bir video veya ses izi içermiyor."
    override val errStartExceedsDuration: (String) -> String = { dur -> "Başlangıç zamanı video süresinden ($dur) küçük olmalıdır." }
    override val errEncodingInProgress = "Devam eden bir işlem var. Yeni dosya seçmek için önce mevcut işlemi bitirin veya iptal edin."
    override val outputFileNameHeader = "ÇIKTI DOSYA ADI"
    override val ffmpegCommandPreview = "FFmpeg Komutu Önizleme"
    override val actionHide = "Gizle"
    override val actionShow = "Göster"

    override val twoPassTitle = "Gerçek 2-Pass Hedef Boyut Hesabı"
    override val twoPassSub = "Tek seferlik %4 güvenlik payı (MB × 8000 × 0.96) ile kapsayıcı taşması önlenir"
    override val targetMbInputLabel = "Hedef Boyut (MB) · Örn: 1, 8, 25"
    override val manualDurationLabel = "Süre okunamadı · Saniye olarak girin"
    override val secondsUnitShort = "sn"
    override val twoPassSummaryLine: (String, String, String, Int, Int, String) -> String = { mb, codec, bit, vkbps, abps, preset ->
        "Hedef: < $mb MB  |  ${codec}${bit} ${vkbps}k  |  Ses: ${abps}k  |  $preset"
    }
    override val lowBitrateWarning = "Not: Hedef/ses oranına göre video bitrati çok düşük, boyut aşabilir."

    override val previewStartTag = "BAŞLANGIÇ"
    override val previewEndTag = "BİTİŞ"
    override val previewFrameCd: (String, String) -> String = { tag, time -> "$tag karesi ($time)" }
    override val previewFrameFallbackCd: (String) -> String = { tag -> "$tag karesi" }
    override val playVideoButton = "Videoyu Oynat"
    override val trimStartLabel = "Başlangıç"
    override val trimEndLabel = "Bitiş"
    override val trimStartPlaceholder = "00:00 / sn (ör. 15)"
    override val trimStartSummaryDefault = "00:00"
    override val trimEndPlaceholder = "Son"
    override val trimEndExceedsDurationWarning: (String) -> String = { dur -> "Bitiş süresi video uzunluğunu ($dur) aşıyor!" }
    override val trimEndOutOfBoundsBadge = "SÜRE AŞILDI"
    override val trimStrategyLosslessTitle = "Kayıpsız Kes"
    override val trimStrategyLosslessSub = "Keyframe · Orijinal boyut"
    override val trimStrategyPreciseTitle = "Hassas Kes"
    override val trimStrategyPreciseSub = "Tam saniye · CRF kodlama"
    override val trimLosslessInfo = "Keyframe noktalarından kayıpsız kesilir; görüntü yeniden kodlanmadığı için orijinal kalır ve dosya boyutu kesilen süre oranında aynı kalır."
    override val trimPreciseInfo = "Video tam girdiğiniz saniyeden yeniden kodlanacağı için dosya boyutu epey büyüyebilir. CRF 16 (HEVC için CRF 20) ile kodlandığından neredeyse gözle görülemeyecek seviyede görüntü kaybı olur."
    override val scanningKeyframes = "Keyframe noktaları taranıyor..."
    override val showKeyframesButton = "Keyframe Noktalarını Göster"
    override val startKeyframesLabel = "Başlangıç Keyframe'leri"
    override val endKeyframesLabel = "Bitiş Keyframe'leri"
    override val noNearbyKeyframes = "Yakında keyframe bulunamadı"
    override val selectedKeyframeCd = "Seçili keyframe"
    override val trimModeTitle = "Kesim Modu"
    override val trimModeSub = "Kayıpsız kesimler -noaccurate_seek + -avoid_negative_ts make_zero kullanır"
    override val trimModeDisabledCd = "Hassas kesimde seçilemez"
    override val trimSubFullLosslessTitle = "Tam Kayıpsız"
    override val trimSubFullLosslessSub = "Görüntü + Ses copy (-noaccurate_seek)"
    override val trimSubVideoLosslessAudioAacTitle = "Görüntü Kayıpsız + Ses AAC Değiştir"
    override val trimSubVideoLosslessAudioAacSub = "5.1 / DTS / EAC3 için uyumlu kesim"
    override val trimSubCrfAudioCopyTitle = "Görüntü Değiştir (CRF) + Ses Kayıpsız"
    override val trimSubCrfAudioCopySub = "Hassas kesim · Ses %100 kayıpsız"
    override val trimSubCrfAudioAacTitle = "Görüntü + Ses Değiştir (CRF + AAC)"
    override val trimSubCrfAudioAacSub = "Hassas kesim · Tam yeniden kodlama"

    override val remuxCardTitle = "%100 Kayıpsız MP4 Paketleme"
    override val remuxCardSub = "Görüntü ve sese dokunmadan (-c copy) MP4 kabı yenilenir, moov başlığı dosya başına alınır (+faststart) ve uyumsuz altyazı/veri izleri elenir (-sn -dn)."
    override val remuxCardHighlight = "Saniyeler içinde tamamlanır · Sıfır kalite kaybı"
    override val audioOnlyCardTitle = "Görüntü %100 Kayıpsız Kopyalanır"
    override val audioOnlyCardSub: (String) -> String = { codec -> "Yalnızca ses akışı ($codec) seçilen bitrate'te AAC'ye çevrilir (-c:v copy)." }
    override val audioOnlyCardBody = "Opus, DTS, AC3 veya EAC3 sesli videoları telefon, WhatsApp ve MP4 oynatıcılarla tam uyumlu yapmak için idealdir."
    override val videoOnlyAudioTitle = "Ses Akışı"
    override val videoOnlyAudioCopySub: (String) -> String = { codec -> "Kaynak ses ($codec) yeniden kodlanmadan %100 kayıpsız kopyalanır (-c:a copy)." }
    override val videoOnlyNoAudioSub = "Videoda ses izi yok (-an)."
    override val videoOnlyCrfBody = "H.264 için CRF 16, H.265 için buna denk arşiv kalitesi CRF 20 uygulanır."
    override val audioExtractTitle = "Ses Çıkarma Formatı"
    override val audioExtractSub = "Kaynak ses zaten AAC veya MP3 ise yeniden kodlanmadan %100 kayıpsız kopyalanır"
    override val audioExtractOriginalOption: (String, String) -> String = { codec, ext -> "Orijinal ($codec → .$ext)" }
    override val audioExtractOriginalLabel = "Orijinal Kodek"
    override val customCmdTitle = "Özel FFmpeg Komutu (Yapıştır & Çalıştır)"
    override val customCmdSub = "ChatGPT, Gemini veya herhangi bir yapay zekâdan aldığınız FFmpeg kodunu hiç düzenlemeden doğrudan yapıştırın."
    override val customCmdAiTip = "2-pass, çok satırlı (\\ veya ^), Markdown ``` bloklu, Windows NUL veya farklı girdi dosya adlı (input.mp4, video.mkv vb.) kodlar seçili dosyanıza otomatik uyarlanır."
    override val customCmdPasteButton = "Panodan Yapıştır"
    override val customCmdClearButton = "Temizle"
    override val customCmdShortcutsLabel = "Hazır örnekler (dokunarak dene):"
    override val customCmdQuickRemuxChip = "Hızlı Remux"
    override val customCmdQuickTwoPassChip = "2-Pass (20MB)"
    override val customCmdQuickGifChip = "GIF (Paletli)"
    override val customCmdQuickMuteChip = "Sesi Kaldır"
    override val customCmdDefaultOutput = "cikti.mp4"
    override val customCmdInputLabel = "FFmpeg Kodunu Buraya Yapıştırın"
    override val customCmdPlaceholder = "Örn. yapay zekâdan kopyalanan tek veya çoklu (2-pass) FFmpeg komutu..."
    override val customCmdStepsDetected: (Int) -> String = { count ->
        if (count > 1) "$count aşamalı zincir komut algılandı" else "1 FFmpeg komutu hazır"
    }
    override val customCmdBadgeInputAutoBound = "Seçili dosya otomatik bağlandı"
    override val customCmdBadgeTwoPassReady = "2-Pass log & null yolu ayarlandı"
    override val customCmdBadgeMarkdownCleaned = "Kod bloğu / açıklamalar temizlendi"
    override val customCmdBadgeWindowsNullFixed = "NUL → /dev/null"
    override val customCmdBadgeOutputAutoAdded = "Çıktı adı otomatik eklendi"
    override val customCmdBadgeSyntaxFixed = "Yazım / boşluk düzeltildi"
    override val customCmdBadgeAndroidCodecAdapted = "Android kodeğine uyarlandı"
    override val customCmdNoValidCommandWarning = "Metinde çalıştırılabilir FFmpeg komutu bulunamadı."

    override val passBadge: (Int, Int) -> String = { p, t -> "GEÇİŞ $p/$t" }
    override val processingBadge = "İŞLENİYOR"
    override val formatPercent: (Int) -> String = { pct -> "%$pct" }
    override val cpuBoostWithFreq: (String, Int) -> String = { ghz, cores -> "Prime ($ghz) öncelikli · $cores çekirdek tam güç" }
    override val cpuBoostGeneric: (Int) -> String = { cores -> "Prime çekirdek öncelikli · $cores çekirdek tam güç" }
    override val wakeLockActiveSuffix = "Uyandırma kilidi aktif."
    override val enableMagicCapsuleButton = "Magic Capsule / Canlı Güncelleme İznini Aç"
    override val successCd = "Başarılı"
    override val successTitle = "İşlem Tamamlandı"
    override val successMediaStoreNote = "Galeri ve Son Dosyalar için MediaStore anında güncellendi."
    override val smallerPercentBadge: (Int) -> String = { pct -> "%$pct daha küçük" }
    override val failedCancelledTitle = "İşlem İptal Edildi"
    override val failedErrorTitle = "Hata Oluştu"
    override val logConsoleHeader: (Int) -> String = { count -> "Durum ve Hata Günlüğü ($count)" }
    override val logConsoleEmpty = "Başlatıldı, aşama değişiklikleri ve varsa hatalar burada gösterilir."

    override val errAnalyzeFailed: (String) -> String = { detail -> "Dosya analiz edilemedi: $detail" }
    override val errSelectMediaFirst = "Önce yukarıdan bir video veya dosya seçin."
    override val errNoVideoTrack = "Bu dosyada video akışı bulunamadı! Sesi Çıkart veya Özel Komut kullanın."
    override val errNoAudioTrack = "Bu videoda ses izi bulunamadı!"
    override val errEnterTrimTimeFirst = "Önce başlangıç veya bitiş zamanı girin."
    override val errInvalidStartTime = "Başlangıç zamanı geçersiz (Örn: 00:01:00 veya 01:30)."
    override val errInvalidEndTime = "Bitiş zamanı geçersiz (Örn: 00:02:32 veya 02:32)."
    override val errEndBeforeStart = "Hata: Bitiş zamanı başlangıçtan büyük olmalı!"
    override val errKeyframeRequiresVideo = "Bu dosyada video akışı yok, keyframe taraması yalnızca video için çalışır."
    override val errKeyframeScanFailed: (String) -> String = { msg -> "Keyframe taraması başarısız: $msg" }
    override val errInvalidTargetMb = "Geçerli bir MB değeri girin (Örn: 1, 8, 25)."
    override val errEnterManualDuration = "Video süresi okunamadı, lütfen süreyi saniye olarak girin."
    override val errNoTrimRangeEntered = "Başlangıç veya bitiş zamanı girmediniz!"
    override val errInvalidTimeFormat = "Zaman formatı hatalı (Örn: 01:30 veya 00:01:30)."
    override val errEnterCustomCommand = "Çalıştırmak için bir FFmpeg komutu girin."

    override val notifChannelName = "Medya İşleme"
    override val notifChannelDesc = "FFmpeg dönüştürme ve kesim ilerleme bildirimi"
    override val notifPreparing = "Hazırlanıyor..."
    override val logTagStarted = "[Başlatıldı]"
    override val logTagStep: (Int, Int) -> String = { p, t -> "[Aşama $p/$t]" }
    override val logTagError = "[Hata]"
    override val logTagCompleted = "[Tamamlandı]"
    override val errFfmpegExitCode: (Int) -> String = { code -> "FFmpeg hata kodu ile durdu (kod: $code)." }
    override val errOutputNotCreated = "Çıktı dosyası oluşturulamadı."
    override val errUnexpected = "Beklenmeyen bir hata oluştu."
    override val successOutputSize: (String) -> String = { size -> "Başarılı! Çıktı Boyutu: $size" }
    override val errCustomCmdEmpty = "Geçerli bir FFmpeg komutu girilmedi."
    override val hintCustomCmdExample = "Örnek: ffmpeg -i \"\$IN\" -c:v libx264 -crf 20 cikti.mp4"
    override val customCmdStepMulti: (Int, Int) -> String = { p, t -> "Komut $p/$t çalıştırılıyor" }
    override val customCmdStepSingle = "Özel FFmpeg komutu çalıştırılıyor"
    override val customCmdOutputLabel = "Özel Komut"
    override val errCustomCmdFailed: (Int) -> String = { code -> "Özel FFmpeg komutu başarısız oldu (kod: $code)." }
    override val hintCustomCmdSyntax: (String) -> String = { _ -> "Hata günlüğündeki FFmpeg mesajına göz atın; desteklenmeyen kodek veya filtre yazım hatası olabilir." }
    override val successCustomCmdDoneNoFiles = "Başarılı! Komut tamamlandı."
    override val successCustomCmdSavedFiles: (Int, String) -> String = { count, total -> "Başarılı! $count dosya kaydedildi ($total)" }
    override val bannerTrimRange: (String, String, String) -> String = { s, e, sub -> "Aralık: $s → $e | $sub" }
    override val bannerRemux = "Kayıpsız MP4 kutu yenileme (-c copy +faststart)"
    override val bannerAudioOnly: (Int) -> String = { ab -> "Görüntü %100 copy | Ses: ${ab}k AAC" }
    override val bannerVideoOnly: (String, String, Int, String) -> String = { codec, bit, crf, preset -> "${codec}${bit} CRF $crf ($preset) | Ses copy" }
    override val bannerSilentAudio = "Sessiz"
    override val bannerExtractAudio: (String) -> String = { fmt -> "Ses Çıkarma · $fmt" }
    override val bannerCustomCmd = "Özel FFmpeg Komutu (Çıktılar otomatik taşınır)"
    override val hintTrimLosslessAudio = "İpucu: Ses kodeği MP4 kutusuna uymuyorsa (ör. Opus/DTS/EAC3) 2. kesim modunu (Görüntü Kayıpsız + Ses AAC Değiştir) seçin."
    override val hintVideoOnlyAudio = "İpucu: Ses kodeği MP4 kutusuna uymuyorsa 10 · Görüntü + Ses Değiştir seçeneğini kullanın."
    override val hintRemuxCompat = "İpucu: Ses veya video kodeği MP4 kabıyla uyumsuzsa 08 · Sadece Ses Değiştir veya 10 · Görüntü + Ses Değiştir seçeneğini deneyin."
    override val errSafReadFailed = "Dosya okuma erişimi alınamadı (SAF)"
    override val errUnknown = "Bilinmeyen hata"
    override val errMediaStoreInsertFailed = "MediaStore kaydı oluşturulamadı"
    override val errOutputStreamFailed = "Çıktı akışı açılamadı"
    override val errSaveFailed: (String) -> String = { detail -> "Dosya kaydedilemedi: $detail" }
    override val errUnknownMediaStore = "Bilinmeyen MediaStore hatası"

    override val telemetryPrefix = "Süre"
    override val stepPass1 = "1/2 Geçiş (Analiz ediliyor)"
    override val stepPass2 = "2/2 Geçiş (Kodlanıyor)"
    override val stepTrimLossless = "Kayıpsız kesiliyor (-noaccurate_seek)"
    override val stepTrimVideoLosslessAudioAac = "Görüntü kayıpsız + Ses AAC kesiliyor"
    override val stepTrimCrfAudioCopy: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Kesilip ${codec}${bit} CRF $crf · Ses copy" }
    override val stepTrimCrfAudioAac: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Kesilip ${codec}${bit} CRF $crf + AAC" }
    override val stepRemux = "Kutu / format yenileniyor (-c copy)"
    override val stepAudioOnly: (Int) -> String = { ab -> "Görüntü copy · Ses ${ab}k AAC" }
    override val stepVideoOnly: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Görüntü ${codec}${bit} CRF $crf · Ses copy" }
    override val stepExtractOriginal: (String, String) -> String = { codec, ext -> "Orijinal ses (%100 kayıpsız: $codec → .$ext)" }
    override val stepExtractAacCopy = "Ses zaten AAC · %100 kayıpsız kopyalanıyor"
    override val stepExtractAacTranscode: (Int) -> String = { ab -> "Ses ${ab}k AAC olarak çıkarılıyor" }
    override val stepExtractMp3Copy = "Ses zaten MP3 · %100 kayıpsız kopyalanıyor"
    override val stepExtractMp3Transcode: (Int) -> String = { ab -> "Ses ${ab}k MP3 olarak çıkarılıyor" }
    override val customCommandOutputPlaceholder = "Komutta belirtilen çıktı"
    override val fileTagClip = "Kesit"
    override val fileTagSilent = "Sessiz"
    override val fileTagOriginal = "Orijinal"
    override val fileTagAudio = "Ses"

    override val actionInAHurry = "Acelem Var"
    override val turboButtonSubBadge = "MediaCodec Hızlı"
    override val turboConfirmTitle = "Donanım Hızlandırmaya Geçilsin mi?"
    override val turboConfirmBody = "MediaCodec donanım kodlayıcısı (Media3) kullanılacak ve işlem çok daha hızlı tamamlanacaktır.\n\nAncak şu anki FFmpeg yöntemi gibi videoyu önden analiz edip sahnelere akıllı bitrate dağıtımı yapmaz; bu nedenle görüntü kalitesi FFmpeg kadar yüksek olmayabilir.\n\nSeçtiğiniz tüm ayarlar (kodek, çözünürlük, FPS, hedef boyut vb.) aynen korunur; mevcut işlem anında iptal edilip donanım motoruyla baştan başlar."
    override val turboConfirmQuestion = "Devam etmek istediğinizden emin misiniz?"
    override val turboConfirmYes = "Evet, Hızlandır"
    override val turboConfirmNo = "Vazgeç"
    override val turboScreenTitle = "Turbo · MediaCodec"
    override val turboGaugeSubLabel = "DONANIM HIZLANDIRMA"
    override val turboHardwareChip = "Zero-Copy GPU · MediaCodec HW"
    override val turboStatusPreparing = "MediaCodec donanım motoru hazırlanıyor..."
    override val turboStatusEncoding = ""
    override val turboStatusSaving = "Çıktı Movies/FFmpeg klasörüne kaydediliyor..."
    override val turboElapsedLabel: (String) -> String = { t -> "Geçen Süre: $t" }
    override val turboSuccessBadge = "MediaCodec Donanım Çıktısı"
    override val turboUnsupportedTitle = "Format Donanım Motorunda Desteklenmiyor"
    override val turboUnsupportedBody = "Bu dosyanın video/ses kodeği veya kapsayıcı biçimi cihazdaki Android MediaCodec donanım motoru tarafından tanınmadı ya da desteklenmiyor."
    override val turboUnsupportedHint = "İpucu: FFmpeg yazılım motoru MKV, DTS, AC3, FLAC ve özel 10-bit formatlar dahil neredeyse her dosyayı sorunsuz işler. Aynı ayarlarla FFmpeg'e dönebilirsiniz."
    override val turboErrorGenericHint = "İpucu: Cihaz donanım kodlayıcısı seçili ayarlarla bu videoyu işleyemedi. Aynı ayarlarla FFmpeg motorunda devam edebilirsiniz."
    override val turboFallbackToFfmpegButton = "FFmpeg ile Devam Et"
    override val turboFallbackFastFfmpegButton = "FFmpeg (Fast) ile Devam Et"
    override val turboNotifTitle = "Turbo · MediaCodec"
    override val turboSettingsPreservedNote = "Seçili tüm ayarlar korundu"
    override val turboRemuxPromptTitle = "Kayıpsız Format Değişimi Denensin mi?"
    override val turboRemuxPromptBody = "Bu format cihazdaki MediaCodec donanım motoru tarafından doğrudan tanınmadı.\n\nÖnce FFmpeg ile saniyeler içinde %100 kayıpsız MP4 format değişimi (-c copy) denenip, başarılı olursa tekrar sormadan otomatik olarak hız göstergesi ekranına dönülerek kodlama yeniden başlatılsın mı?"
    override val turboRemuxPromptYes = "Evet, Dene"
    override val turboRemuxPromptNo = "Hayır"
    override val turboTryRemuxButton = "Kayıpsız Format Değişimi Dene"
    override val turboRemuxModeTitle = "Format Değiştir → Turbo"
    override val turboRemuxStepStatus = "MediaCodec için kayıpsız MP4 formatına çevriliyor (-c copy)"
    override val turboAfterRemuxFailedTitle = "Donanım Hızlandırma Bu Dosyada Desteklenmiyor"
    override val turboAfterRemuxFailedBody = "Kayıpsız format değişimi denendi ancak bu dosyanın video veya ses akışı cihazdaki Android MediaCodec donanım motoruyla uyumlu değil.\n\nBu dosya için ya önce yeniden dönüştürme yapılması ya da doğrudan FFmpeg motorunun kullanılması gerekiyor."
    override val turboAfterRemuxFastHint = "Tavsiye: Hızlı ve sorunsuz sonuç için FFmpeg'i 'fast' (hızlı) preset ile doğrudan kullanabilirsiniz; FFmpeg tüm kodek ve formatları kararlı biçimde işler."
}

object EnglishStrings : AppStrings {
    override val language = AppLanguage.EN
    override val languageSelectorCd = "Change language"
    override val languageAutoSystem = "System Language (Auto)"
    override val historyTitle = "Recent Outputs"
    override val historyClear = "Clear"
    override val historyClearCd = "Clear history"
    override val historyEmpty = "No processed files yet. Select a video to get started."
    override val batteryOptDialogTitle = "Ignore Battery Optimizations"
    override val batteryOptDialogBody = "Disable battery optimization so FFmpeg can run CPU cores at full performance and avoid being throttled or stopped by the system during background encodings."
    override val batteryOptAllowButton = "Allow"
    override val batteryOptLaterButton = "Later"
    override val creditsInfoCd = "Library & open-source credits"
    override val creditsDialogTitle = "Open-Source Credits"
    override val creditsDialogSubtitle = "This application stands on the shoulders of the following open-source libraries and their authors."
    override val creditsCoreSectionLabel = "Core Media Engine"
    override val creditsCoreCreators = "Creator: Fabrice Bellard · Lead & Team: Michael Niedermayer & FFmpeg Developers"
    override val creditsCoreEncoders = "Bundled encoders: x264 (VideoLAN), x265 (MulticoreWare), LAME MP3"
    override val creditsOriginalLibSectionLabel = "Original Android Library"
    override val creditsOriginalLibCreator = "Author: Taner Şener (ARTHENICA)"
    override val creditsForkSectionLabel = "Maintained Fork Used"
    override val creditsForkMaintainer = "Maintainer: Jokobee & ffmpegkit-maintained Contributors"
    override val creditsMedia3SectionLabel = "Hardware Acceleration Engine"
    override val creditsMedia3Author = "Author: Google / Android Open Source Project (Apache 2.0)"
    override val actionClose = "Close"

    override val actionBack = "Back"
    override val actionCancel = "Cancel"
    override val actionStart = "Start Processing"
    override val actionOpen = "Play / Open"
    override val actionShare = "Share"
    override val actionNewTask = "New Task"
    override val actionHome = "Home"
    override val actionBackToSettings = "Back to Settings"
    override val toastNoAppToOpenFile = "No app found to open this file."
    override val toastShareFailed = "Unable to start sharing."
    override val chooserPlayVideoWith = "Play video with"
    override val toastNoAppToPlayVideo = "No app found to play this video."

    override val analyzingMedia = "Analyzing media..."
    override val selectSourceMedia = "Select Source Media"
    override val pickGalleryVideo = "Gallery (Video)"
    override val pickFile = "Pick File"
    override val changeFile = "Change"
    override val changeFileCd = "Change source file"
    override val audioOnlyTag = "AUDIO"
    override val audioTrackPrefix = "Audio"
    override val noneLabel = "None"
    override val evenDimensionFixChip = "Even-Dimension Fix"

    override val noVideoBadge = "No video"
    override val noAudioBadge = "No audio"
    override val catTargetSize = "Target Size"
    override val catTrim = "Trim Video"
    override val catOther = "More Tools"

    override val modeCustomSizeTitle = "Custom Size"
    override val modeCustomSizeShortSub = "Enter MB"
    override val modeCustomSizeSub = "Custom MB target · 2-Pass ABR"
    override val modeDiscordSub = "< 20 MB · True 2-Pass ABR"
    override val modeWhatsappSub = "< 100 MB · True 2-Pass ABR"
    override val modeGmailSub = "< 25 MB · True 2-Pass ABR"
    override val modeTrimTitle = "Trim Video"
    override val modeTrimSub = "Smart keyframe scan · Lossless / Frame-accurate"
    override val modeTrimShortSub = "Keyframe · Exact"
    override val modeRemuxTitle = "Change Format"
    override val modeRemuxShortTitle = "Change Format"
    override val modeRemuxSub = "100% lossless MP4 container remux (-c copy)"
    override val modeExtractAudioTitle = "Extract Audio"
    override val modeExtractAudioSub = "Original (Lossless) / AAC / MP3"
    override val modeAudioOnlyTitle = "Re-encode Audio Only"
    override val modeAudioOnlySub = "Video stream copy · Audio to AAC"
    override val modeVideoOnlyTitle = "Re-encode Video Only"
    override val modeVideoOnlySub = "CRF 16/20 · Audio stream copy"
    override val modeVideoAndAudioTitle = "Re-encode Video + Audio"
    override val modeVideoAndAudioSub = "CRF 16/20 · 8/10-bit · AAC"
    override val modeCustomCmdTitle = "Custom FFmpeg Command"
    override val modeCustomCmdSub = "Paste full CLI command (supports && chains)"
    override val badgeLossless = "Lossless"
    override val badgeLossy = "Lossy"

    override val codecSectionTitle = "Video Codec & Bit Depth"
    override val codecSectionSub: (String, String) -> String = { codec, bitDepth -> "Source: $codec · $bitDepth · H.265 outputs automatically include -tag:v hvc1" }
    override val colorBitDepthLabel = "Color Bit Depth"
    override val bit8Compatible = "8-bit (Compatible)"
    override val bit8Default = "8-bit (Default)"
    override val bit10Preserve = "10-bit (Preserve)"
    override val nonNativeTenBitWarning: (String) -> String = { src -> "Warning: Source video is $src (not 10-bit). Encoding an 8-bit source as 10-bit adds no real color depth, slows down encoding, and may reduce device compatibility." }
    override val fpsResSectionTitle = "Frame Rate & Smart Scaling"
    override val fpsResSectionSub = "3-way aspect preservation: Portrait (9:16 / 4:5), CinemaScope (2.39:1), and 16:9 auto-detected"
    override val recommend30Fps720pText: (Int) -> String = { vkbps -> "Bitrate drops too low for this video length (${vkbps}k). 30 FPS and 720p are recommended for cleaner visual quality." }
    override val fpsOriginalLabel = "Original FPS"
    override val fpsKeepSub = "Keep"
    override val targetResolutionLabel = "Target Resolution"
    override val resOriginalLabel = "Original"
    override val activeFilterPrefix = "Active Filter:"
    override val presetSectionTitle = "Encoding Speed (Preset)"
    override val presetCrfSub: (String) -> String = { rec -> "Recommended: $rec · In CRF encoding, slower presets produce smaller files while keeping the exact same visual quality." }
    override val presetTargetSizeSub: (String) -> String = { rec -> "Recommended: $rec" }
    override val presetTargetSizeHighlight = "Slower presets improve visual quality; because they compress more efficiently into smaller sizes, they deliver higher compression quality at your target MB."
    override val presetVerySlowNote = "Note: The real-world quality/efficiency difference between slow and veryslow is only ~2–4%, while encoding takes roughly 2–3× longer."
    override val audioBitrateTitle = "Audio Bitrate (AAC · Lossy)"
    override val audioBitrateExtractTitle: (String) -> String = { fmt -> "Output Audio Bitrate ($fmt)" }
    override val audioBitrateSub: (Int) -> String = { kbps -> "Supports 32k – 768k (including 320k/384k+ for 5.1 surround) · Active: ${kbps}k" }
    override val audioBitrateMp3Sub: (Int) -> String = { kbps -> "Supports 32k – 320k for MP3 · Active: ${kbps}k" }
    override val audioBitrateSourceCapSub: (Int, Int) -> String = { src, kbps -> "Source audio: ${src}k · Quick presets capped to source quality · Active: ${kbps}k" }
    override val audioBitrateUpscaleWarning: (Int, String, Int) -> String = { src, codec, req -> "Warning: Source audio is ${src}k ($codec). Entering a higher bitrate (${req}k) does not improve audio quality and only inflates file size." }
    override val customBitrateLabel = "Custom Bitrate (32–768 kbps)"
    override val customBitrateMp3Label = "Custom Bitrate (32–320 kbps)"
    override val cancelConfirmTitle = "Cancel Processing?"
    override val cancelConfirmBody = "The current operation will be stopped and progress so far will be discarded. Are you sure?"
    override val cancelConfirmYes = "Yes, Cancel"
    override val cancelConfirmKeepRunning = "Keep Running"
    override val errUnsupportedMediaFile = "This file does not contain a valid video or audio track."
    override val errStartExceedsDuration: (String) -> String = { dur -> "Start time must be less than video duration ($dur)." }
    override val errEncodingInProgress = "An operation is already running. Finish or cancel it before selecting another file."
    override val outputFileNameHeader = "OUTPUT FILENAME"
    override val ffmpegCommandPreview = "FFmpeg Command Preview"
    override val actionHide = "Hide"
    override val actionShow = "Show"

    override val twoPassTitle = "True 2-Pass Target Size Calculation"
    override val twoPassSub = "Applies a 4% muxing overhead margin (MB × 8000 × 0.96) to prevent container overflow"
    override val targetMbInputLabel = "Target Size (MB) · e.g. 1, 8, 25"
    override val manualDurationLabel = "Duration unavailable · Enter in seconds"
    override val secondsUnitShort = "s"
    override val twoPassSummaryLine: (String, String, String, Int, Int, String) -> String = { mb, codec, bit, vkbps, abps, preset ->
        "Target: < $mb MB  |  ${codec}${bit} ${vkbps}k  |  Audio: ${abps}k  |  $preset"
    }
    override val lowBitrateWarning = "Note: Video bitrate is very low for this target/audio budget; output may slightly exceed the target size."

    override val previewStartTag = "START"
    override val previewEndTag = "END"
    override val previewFrameCd: (String, String) -> String = { tag, time -> "$tag frame ($time)" }
    override val previewFrameFallbackCd: (String) -> String = { tag -> "$tag frame" }
    override val playVideoButton = "Play Video"
    override val trimStartLabel = "Start"
    override val trimEndLabel = "End"
    override val trimStartPlaceholder = "00:00 / sec (e.g. 15)"
    override val trimStartSummaryDefault = "00:00"
    override val trimEndPlaceholder = "End"
    override val trimEndExceedsDurationWarning: (String) -> String = { dur -> "End time exceeds video duration ($dur)!" }
    override val trimEndOutOfBoundsBadge = "OUT OF RANGE"
    override val trimStrategyLosslessTitle = "Lossless Cut"
    override val trimStrategyLosslessSub = "Keyframes · Original size"
    override val trimStrategyPreciseTitle = "Exact Cut"
    override val trimStrategyPreciseSub = "Frame-accurate · CRF encode"
    override val trimLosslessInfo = "Cuts losslessly on keyframe boundaries without re-encoding; video stays 100% original and file size scales proportionally with the trimmed duration."
    override val trimPreciseInfo = "Re-encodes the video at your exact timestamps, which may noticeably increase file size. Encoded at CRF 16 (CRF 20 for HEVC) for virtually indistinguishable visual quality."
    override val scanningKeyframes = "Scanning keyframes..."
    override val showKeyframesButton = "Show Keyframe Points"
    override val startKeyframesLabel = "Start Keyframes"
    override val endKeyframesLabel = "End Keyframes"
    override val noNearbyKeyframes = "No nearby keyframes found"
    override val selectedKeyframeCd = "Selected keyframe"
    override val trimModeTitle = "Trim Mode"
    override val trimModeSub = "Lossless cuts use -noaccurate_seek + -avoid_negative_ts make_zero"
    override val trimModeDisabledCd = "Unavailable in Exact Cut mode"
    override val trimSubFullLosslessTitle = "Fully Lossless"
    override val trimSubFullLosslessSub = "Video + Audio stream copy (-noaccurate_seek)"
    override val trimSubVideoLosslessAudioAacTitle = "Lossless Video + Convert Audio to AAC"
    override val trimSubVideoLosslessAudioAacSub = "Compatible trim for 5.1 / DTS / EAC3 audio"
    override val trimSubCrfAudioCopyTitle = "Re-encode Video (CRF) + Lossless Audio"
    override val trimSubCrfAudioCopySub = "Frame-accurate cut · 100% lossless audio copy"
    override val trimSubCrfAudioAacTitle = "Re-encode Video + Audio (CRF + AAC)"
    override val trimSubCrfAudioAacSub = "Frame-accurate cut · Full re-encode"

    override val remuxCardTitle = "100% Lossless MP4 Remux"
    override val remuxCardSub = "Rewraps streams into an MP4 container without re-encoding (-c copy), moves the moov atom to the front (+faststart), and strips incompatible subtitle/data tracks (-sn -dn)."
    override val remuxCardHighlight = "Completes in seconds · Zero quality loss"
    override val audioOnlyCardTitle = "100% Lossless Video Stream Copy"
    override val audioOnlyCardSub: (String) -> String = { codec -> "Only the audio track ($codec) is transcoded to AAC at the selected bitrate (-c:v copy)." }
    override val audioOnlyCardBody = "Ideal for making Opus, DTS, AC3, or EAC3 videos fully compatible with mobile players, WhatsApp, and standard MP4 playback."
    override val videoOnlyAudioTitle = "Audio Stream"
    override val videoOnlyAudioCopySub: (String) -> String = { codec -> "Source audio ($codec) is copied losslessly without re-encoding (-c:a copy)." }
    override val videoOnlyNoAudioSub = "No audio track in source video (-an)."
    override val videoOnlyCrfBody = "Applies CRF 16 for H.264 or equivalent archival-grade CRF 20 for H.265."
    override val audioExtractTitle = "Audio Extraction Format"
    override val audioExtractSub = "If the source audio is already AAC or MP3, it is stream-copied losslessly without re-encoding"
    override val audioExtractOriginalOption: (String, String) -> String = { codec, ext -> "Original ($codec → .$ext)" }
    override val audioExtractOriginalLabel = "Original Codec"
    override val customCmdTitle = "Custom FFmpeg Command (Paste & Run)"
    override val customCmdSub = "Paste any FFmpeg snippet straight from ChatGPT, Gemini, or any AI without manual editing."
    override val customCmdAiTip = "2-pass chains, multi-line (\\ or ^), Markdown ``` blocks, Windows NUL, and any placeholder filename (input.mp4, video.mkv, etc.) are auto-adapted to your selected file."
    override val customCmdPasteButton = "Paste from Clipboard"
    override val customCmdClearButton = "Clear"
    override val customCmdShortcutsLabel = "Quick templates (tap to insert):"
    override val customCmdQuickRemuxChip = "Quick Remux"
    override val customCmdQuickTwoPassChip = "2-Pass (20MB)"
    override val customCmdQuickGifChip = "Palette GIF"
    override val customCmdQuickMuteChip = "Remove Audio"
    override val customCmdDefaultOutput = "output.mp4"
    override val customCmdInputLabel = "Paste FFmpeg Code Here"
    override val customCmdPlaceholder = "e.g., any single or multi-step (2-pass) FFmpeg snippet copied from an AI..."
    override val customCmdStepsDetected: (Int) -> String = { count ->
        if (count > 1) "$count-step command chain detected" else "1 FFmpeg command ready"
    }
    override val customCmdBadgeInputAutoBound = "Selected file auto-linked"
    override val customCmdBadgeTwoPassReady = "2-Pass log & null path ready"
    override val customCmdBadgeMarkdownCleaned = "Markdown / prose cleaned"
    override val customCmdBadgeWindowsNullFixed = "NUL → /dev/null"
    override val customCmdBadgeOutputAutoAdded = "Output filename auto-added"
    override val customCmdBadgeSyntaxFixed = "Syntax / spacing auto-fixed"
    override val customCmdBadgeAndroidCodecAdapted = "Adapted to Android codec"
    override val customCmdNoValidCommandWarning = "No runnable FFmpeg command found in this text."

    override val passBadge: (Int, Int) -> String = { p, t -> "PASS $p/$t" }
    override val processingBadge = "PROCESSING"
    override val formatPercent: (Int) -> String = { pct -> "$pct%" }
    override val cpuBoostWithFreq: (String, Int) -> String = { ghz, cores -> "Prime ($ghz) priority · $cores cores full power" }
    override val cpuBoostGeneric: (Int) -> String = { cores -> "Prime core priority · $cores cores full power" }
    override val wakeLockActiveSuffix = "Wake lock active."
    override val enableMagicCapsuleButton = "Enable Magic Capsule / Live Updates"
    override val successCd = "Success"
    override val successTitle = "Processing Complete"
    override val successMediaStoreNote = "MediaStore updated immediately for Gallery and Recent Files."
    override val smallerPercentBadge: (Int) -> String = { pct -> "$pct% smaller" }
    override val failedCancelledTitle = "Task Cancelled"
    override val failedErrorTitle = "Encoding Failed"
    override val logConsoleHeader: (Int) -> String = { count -> "Status & Error Log ($count)" }
    override val logConsoleEmpty = "Startup status, pass transitions, and any errors will appear here."

    override val errAnalyzeFailed: (String) -> String = { detail -> "Failed to analyze file: $detail" }
    override val errSelectMediaFirst = "Select a video or media file above first."
    override val errNoVideoTrack = "No video stream found in this file. Use Extract Audio or Custom Command."
    override val errNoAudioTrack = "No audio track found in this file."
    override val errEnterTrimTimeFirst = "Enter a start or end timestamp first."
    override val errInvalidStartTime = "Invalid start timestamp (e.g., 00:01:00 or 01:30)."
    override val errInvalidEndTime = "Invalid end timestamp (e.g., 00:02:32 or 02:32)."
    override val errEndBeforeStart = "Error: End time must be greater than start time."
    override val errKeyframeRequiresVideo = "No video stream in this file; keyframe scanning requires video."
    override val errKeyframeScanFailed: (String) -> String = { msg -> "Keyframe scan failed: $msg" }
    override val errInvalidTargetMb = "Enter a valid target size in MB (e.g., 1, 8, 25)."
    override val errEnterManualDuration = "Could not detect video duration. Please enter duration in seconds."
    override val errNoTrimRangeEntered = "Please enter a start or end time."
    override val errInvalidTimeFormat = "Invalid timestamp format (e.g., 01:30 or 00:01:30)."
    override val errEnterCustomCommand = "Enter an FFmpeg command to run."

    override val notifChannelName = "Media Processing"
    override val notifChannelDesc = "FFmpeg transcoding and trimming progress notification"
    override val notifPreparing = "Preparing..."
    override val logTagStarted = "[Started]"
    override val logTagStep: (Int, Int) -> String = { p, t -> "[Step $p/$t]" }
    override val logTagError = "[Error]"
    override val logTagCompleted = "[Completed]"
    override val errFfmpegExitCode: (Int) -> String = { code -> "FFmpeg exited with an error (code: $code)." }
    override val errOutputNotCreated = "Output file was not created."
    override val errUnexpected = "An unexpected error occurred."
    override val successOutputSize: (String) -> String = { size -> "Done! Output size: $size" }
    override val errCustomCmdEmpty = "No valid FFmpeg command entered."
    override val hintCustomCmdExample = "Example: ffmpeg -i \"\$IN\" -c:v libx264 -crf 20 output.mp4"
    override val customCmdStepMulti: (Int, Int) -> String = { p, t -> "Running command $p/$t" }
    override val customCmdStepSingle = "Running custom FFmpeg command"
    override val customCmdOutputLabel = "Custom Command"
    override val errCustomCmdFailed: (Int) -> String = { code -> "Custom FFmpeg command failed (code: $code)." }
    override val hintCustomCmdSyntax: (String) -> String = { _ -> "Check the FFmpeg message in the error log; an unsupported codec or filter syntax error may be present." }
    override val successCustomCmdDoneNoFiles = "Done! Command completed."
    override val successCustomCmdSavedFiles: (Int, String) -> String = { count, total ->
        if (count == 1) "Done! Saved 1 file ($total)" else "Done! Saved $count files ($total)"
    }
    override val bannerTrimRange: (String, String, String) -> String = { s, e, sub -> "Range: $s → $e | $sub" }
    override val bannerRemux = "Lossless MP4 container remux (-c copy +faststart)"
    override val bannerAudioOnly: (Int) -> String = { ab -> "Video stream copy | Audio: ${ab}k AAC" }
    override val bannerVideoOnly: (String, String, Int, String) -> String = { codec, bit, crf, preset -> "${codec}${bit} CRF $crf ($preset) | Audio copy" }
    override val bannerSilentAudio = "Silent"
    override val bannerExtractAudio: (String) -> String = { fmt -> "Extract Audio · $fmt" }
    override val bannerCustomCmd = "Custom FFmpeg Command (Outputs saved automatically)"
    override val hintTrimLosslessAudio = "Tip: If the audio codec isn't MP4-compatible (e.g., Opus/DTS/EAC3), select trim mode 2 (Lossless Video + Convert Audio to AAC)."
    override val hintVideoOnlyAudio = "Tip: If the audio codec isn't MP4-compatible, use 10 · Re-encode Video + Audio instead."
    override val hintRemuxCompat = "Tip: If the video or audio codec is incompatible with MP4, try 08 · Re-encode Audio Only or 10 · Re-encode Video + Audio."
    override val errSafReadFailed = "Unable to open file for reading (SAF)"
    override val errUnknown = "Unknown error"
    override val errMediaStoreInsertFailed = "Could not create MediaStore entry"
    override val errOutputStreamFailed = "Could not open output stream"
    override val errSaveFailed: (String) -> String = { detail -> "Failed to save file: $detail" }
    override val errUnknownMediaStore = "Unknown MediaStore error"

    override val telemetryPrefix = "Time"
    override val stepPass1 = "Pass 1/2 (Analyzing)"
    override val stepPass2 = "Pass 2/2 (Encoding)"
    override val stepTrimLossless = "Trimming losslessly (-noaccurate_seek)"
    override val stepTrimVideoLosslessAudioAac = "Trimming (Lossless Video + AAC Audio)"
    override val stepTrimCrfAudioCopy: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Trimming ${codec}${bit} CRF $crf · Audio copy" }
    override val stepTrimCrfAudioAac: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Trimming ${codec}${bit} CRF $crf + AAC" }
    override val stepRemux = "Remuxing container (-c copy)"
    override val stepAudioOnly: (Int) -> String = { ab -> "Video copy · Audio ${ab}k AAC" }
    override val stepVideoOnly: (String, String, Int, String) -> String = { codec, bit, crf, _ -> "Video ${codec}${bit} CRF $crf · Audio copy" }
    override val stepExtractOriginal: (String, String) -> String = { codec, ext -> "Original audio (100% lossless: $codec → .$ext)" }
    override val stepExtractAacCopy = "Audio is already AAC · Lossless stream copy"
    override val stepExtractAacTranscode: (Int) -> String = { ab -> "Extracting audio as ${ab}k AAC" }
    override val stepExtractMp3Copy = "Audio is already MP3 · Lossless stream copy"
    override val stepExtractMp3Transcode: (Int) -> String = { ab -> "Extracting audio as ${ab}k MP3" }
    override val customCommandOutputPlaceholder = "Defined by command"
    override val fileTagClip = "Clip"
    override val fileTagSilent = "Mute"
    override val fileTagOriginal = "Original"
    override val fileTagAudio = "Audio"

    override val actionInAHurry = "In a Hurry"
    override val turboButtonSubBadge = "MediaCodec Fast"
    override val turboConfirmTitle = "Switch to Hardware Acceleration?"
    override val turboConfirmBody = "This will use Android's MediaCodec hardware encoder (Media3) for much faster processing.\n\nHowever, unlike FFmpeg's pre-analysis pass, it does not analyze scenes in advance for smart bitrate distribution, so visual quality will not match FFmpeg.\n\nAll your selected settings (codec, resolution, FPS, target size, etc.) will be preserved; the current FFmpeg task will be cancelled immediately and restarted with hardware acceleration."
    override val turboConfirmQuestion = "Are you sure you want to continue?"
    override val turboConfirmYes = "Yes, Go Turbo"
    override val turboConfirmNo = "Cancel"
    override val turboScreenTitle = "Turbo · MediaCodec"
    override val turboGaugeSubLabel = "HARDWARE ACCEL"
    override val turboHardwareChip = "Zero-Copy GPU · MediaCodec HW"
    override val turboStatusPreparing = "Initializing MediaCodec hardware engine..."
    override val turboStatusEncoding = ""
    override val turboStatusSaving = "Saving output to Movies/FFmpeg..."
    override val turboElapsedLabel: (String) -> String = { t -> "Elapsed: $t" }
    override val turboSuccessBadge = "MediaCodec Hardware Output"
    override val turboUnsupportedTitle = "Format Unsupported by Hardware Engine"
    override val turboUnsupportedBody = "This file's container, video, or audio codec is not recognized or supported by this device's Android MediaCodec hardware pipeline."
    override val turboUnsupportedHint = "Tip: The FFmpeg software engine handles virtually all formats including MKV, DTS, AC3, FLAC, and specialized 10-bit streams. You can seamlessly continue with FFmpeg using the same settings."
    override val turboErrorGenericHint = "Tip: Your device's hardware encoder could not process this video with the selected settings. You can continue on FFmpeg with the exact same settings."
    override val turboFallbackToFfmpegButton = "Continue with FFmpeg"
    override val turboFallbackFastFfmpegButton = "Continue with FFmpeg (Fast)"
    override val turboNotifTitle = "Turbo · MediaCodec"
    override val turboSettingsPreservedNote = "All selected settings preserved"
    override val turboRemuxPromptTitle = "Try Lossless Format Change?"
    override val turboRemuxPromptBody = "This format wasn't recognized directly by your device's MediaCodec hardware engine.\n\nWould you like FFmpeg to try a quick 100% lossless MP4 remux (-c copy) first and, if it succeeds, automatically slide back to the speedometer screen and retry without asking again?"
    override val turboRemuxPromptYes = "Yes, Try It"
    override val turboRemuxPromptNo = "No"
    override val turboTryRemuxButton = "Try Lossless Format Change"
    override val turboRemuxModeTitle = "Change Format → Turbo"
    override val turboRemuxStepStatus = "Remuxing losslessly to MP4 for MediaCodec (-c copy)"
    override val turboAfterRemuxFailedTitle = "Hardware Acceleration Unsupported for This File"
    override val turboAfterRemuxFailedBody = "A lossless format change was tried, but this file's video or audio stream is still incompatible with your device's Android MediaCodec hardware engine.\n\nYou'll need to re-encode the file or use the FFmpeg software engine directly."
    override val turboAfterRemuxFastHint = "Tip: For quick, reliable results, try running FFmpeg directly with the 'fast' preset — FFmpeg handles virtually all codecs and containers."
}

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
