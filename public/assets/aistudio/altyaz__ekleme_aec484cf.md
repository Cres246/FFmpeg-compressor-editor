Aşağıda bu büyük 3 parçalı eklemeden (Altyazıları Koru + Çoklu Format Remux + Etkileşimli Altyazı Stüdyosu) itibaren yapılan tüm değişikliklerin, karşılaşılan hataların/çözümlerinin ve şu anki tam durumun başka bir yapay zekâya doğrudan verilebilecek eksiksiz özeti yer alıyor.

1. Git Commit Geçmişi ve Mevcut Durum
Dal: main (yerel ve origin/main birebir senkron)
Başlangıç noktası (bu görevden önceki HEAD): af8dd60
Bu görevde atılan 3 commit:
30148ed — feat: add Keep Subtitles, multi-format Remux, and interactive Subtitle Studio (24 dosya, +4298 / -155 satır)
29deba1 — fix(ci): pass Nix HOST_CC as CC_FOR_BUILD/CC_BUILD for fribidi and freetype host helpers
a098d3b (güncel HEAD) — fix(compile): resolve nullable Map variance in FfmpegEngine and Box AnimatedVisibility receiver
CI / Önbellek Durumu:
GitHub Actions'taki v6 (ffmpeg-kit-next-arm64-simd-v6-...) FFmpegKit Next ARM64-O3 + libass AAR derlemesi NDK r30 (Clang 21) ile başarıyla tamamlanıp önbelleğe alındı (scripts/ değişmediği için sonraki çalışmalarda yeniden derlenmez).
a098d3b push'u sonrasında Gradle birim testleri ve Release APK (assembleRelease) derlemesi GitHub Actions üzerinde tetiklendi; cihaz üzerinde onaylanmadığı için docs/*.md dosyaları AGENTS.md kuralı gereği henüz güncellenmedi.
2. Yapılan Tüm Özellikler ve Dosya Bazlı Değişiklikler
A) Performans & Kırmızı Çizgi Koruması (AGENTS.md Kural 1, 2, 10, 11, 13, 18, 19, 20)
Termux hız üstünlüğünü koruyan hiçbir katmana dokunulmadı: CpuPerformanceBooster, Scudo M_DECAY_TIME, 256 KB ffkitsaf:, -O3, -Wl,--lto-O3, -Wl,-Bsymbolic-functions, x265 8+10bit multilib, dav1d ASM, sıfır kopya saf: ve kodlama sırasında sıfır polling aynen korundu.
--enable-lib-libass yalnızca libavfilter.so içine bağlanır; altyazı filtresi çağrılmayan kodlamalarda çalışma zamanı maliyeti %0'dır.
app/build.gradle.kts içindeki signingConfigs dokunulmadan bırakıldı; AppStrings arayüz (interface) olarak kaldı (asla data class yapılmadı); com.arthenica.ffmpegkit.* sınıflarında yalnızca açık .getXxx() çağrıları kullanıldı.
B) Video Altyazı Bilgi Çipleri (Üssel Sayı) + Güvenli "Altyazıları Koru" Anahtarı
MediaModels.kt:
SubtitleTrackInfo eklendi (subtitleStreamIndex, globalStreamIndex, codec, language, title, formatTag -> .srt/.ass/.vtt/.tx3g/.pgs/.vobsub, isTextBased, isBitmapBased).
MediaInfo içine subtitleTracks + hasSubtitles, hasTextSubtitles, hasBitmapSubtitles, hasAssSubtitles eklendi.
ModeConfigState içine preserveSubtitles: Boolean = false eklendi.
FfmpegEngine.kt:
probeMedia() artık tüm subtitle akışlarını okuyup MediaInfo.subtitleTracks dolduruyor.
MediaHeroCard.kt:
Videoda altyazı varsa bilgi çipleri arasına SubtitleMetaChip eklenir; aynı biçimden birden fazla iz varsa BaselineShift.Superscript ile üssel sayı gösterilir (ör. Altyazı: .srt¹² · .ass³).
FfmpegMath.kt & ModeConfigScreen.kt:
TRIM_VIDEO, REMUX_FORMAT, VIDEO_ONLY_CRF, VIDEO_AND_AUDIO_CRF ve AUDIO_ONLY_AAC modlarına yalnızca media.hasSubtitles olduğunda görünen PreserveSubtitlesToggleCard (varsayılan kapalı) eklendi.
appendSubtitlePreservationArgs() konteyner güvenliği sağlar:
Kapalıysa: eskisi gibi -sn -dn.
.mkv/.mka: -map 0:s? -c:s copy (+ .ass varsa -map 0:t? -c:t copy gömülü fontlar).
.mp4/.mov/.m4v: yalnızca metin izleri (-map 0:s:<idx>) + -c:s mov_text; PGS/VobSub görsel altyazılar FFmpeg'i çökertmemek için otomatik atlanır ve UI'da not düşülür.
.webm: yalnızca metin izleri + -c:s webvtt.
Diğerleri (ts, avi, flv, 3gp): güvenli -sn.
resolveFullLosslessTrimExtension(media, preserveSubtitles): TRIM_VIDEO Tam Kayıpsız modunda preserveSubtitles = true ve kaynakta .ass/görsel altyazı varsa çıktıyı .mkv tutar; kesim aralığında FFmpeg altyazı zamanlarını otomatik kırpıp senkronize eder.
C) Çoklu Format Değiştirme (REMUX_FORMAT)
MediaModels.kt içine popülerlik sırasıyla RemuxOutputFormat eklendi:
MP4 (★ Önerilen) → MKV → MOV → WEBM → TS → AVI → FLV → M4V → 3GP
FfmpegMath.kt:
-movflags +faststart, -tag:v hvc1 ve aac_adtstoasc yalnızca ISO-BMFF (mp4, mov, m4v, 3gp) kaplarda eklenir.
checkRemuxCompatibilityHint() engelleyici olmayan kodek/kap uyumluluk notları üretir.
resolveMimeType() içine ts, flv, 3gp, srt, ass, vtt eklendi.
ModeConfigScreen.kt: RemuxFormatPickerCard eklendi.
D) Etkileşimli Altyazı Gömme Stüdyosu (ProcessingMode.EMBED_SUBTITLES)
Bağımlılık & Yerel Motor:
libs.versions.toml & app/build.gradle.kts: androidx.media3:media3-exoplayer:1.11.1 eklendi.
build_optimized_ffmpegkit_arm64.sh & build-apk.yml: --enable-lib-libass zorunlu bayrak olarak eklendi (freetype, fribidi, fontconfig, harfbuzz otomatik gelir), Gate 2 ve Release APK doğrulama kapısına ass_render_frame|--enable-libass kontrolü konuldu, önbellek anahtarı v5 → v6 yapıldı.
Tam UTF-8 / Türkçe Fontlar (Önemli Bulgu):
Uygulamanın mevcut 4 UI fontu (res/font/*.ttf) Latin-1 alt kümesi olup Türkçe ğ Ğ ş Ş İ gliflerini içermediği tespit edildi; bu yüzden asla libass'a verilmedi.
app/src/main/assets/subtitle_fonts/ altına 0 eksik glifle doğrulanmış 3 statik Bold .ttf eklendi:
sub_roboto_bold.ttf (Roboto ★)
sub_montserrat_bold.ttf (Montserrat)
sub_ptserif_bold.ttf (PT Serif)
Aynı font dosyaları 3 katmanda birebir kullanılır: Compose canlı önizleme (Typeface.createFromAsset), FFmpeg libass (workDir/fonts/ + fontsdir= + FFmpegKitConfig.setFontDirectoryList), Media3 GPU Hardsub (SubtitleBitmapOverlay).
Ayrıştırıcı & Modeller (SubtitleParser.kt):
SubtitleEmbedMode (SOFTSUB, HARDSUB), SubtitleSoftsubContainer (MP4, MKV), SubtitleFontOption, SubtitleFontSizeOption (SMALL, MEDIUM ★, LARGE, XLARGE), SubtitleCue.
decodeBytesSmart(): UTF-8 / UTF-16 BOM + katı UTF-8 doğrulama + bozuk Türkçe ANSI dosyalar için windows-1254 (CP1254) geri dönüşü.
.srt, .vtt, .ass/.ssa okuma; UTF-8 .srt ve video en-boy oranına ölçekli .ass yazma; FFmpeg filtre yolu kaçışlama (escapeForFfmpegFilterPath); 
𝑂
(
log
⁡
𝑁
)
O(logN) ikili arama (findActiveCueIndex, findVisibleCueRange).
Canlı Video + Sonsuz Şerit + Satır İçi Düzenleyici (SubtitleStudioSection.kt):
Büyük yatay 16:9 sahne içinde Jetpack Media3 ExoPlayer + TextureView (sesli canlı oynatma, harici oynatıcı açmadan videonun üzerinde Oynat/Duraklat düğmesi, dikey/sinemaskop taşma korumalı en-boy oranı).
Media3 uyumsuz kodek/konteyner tespitinde (Media3CompatibilityAdvisor ön kontrolü veya onPlayerError) otomatik olarak tek büyük yatay 120Hz VideoFrameScrubber sessiz kare önizlemesine düşer; isActiveStage == false olduğunda ikisi de anında release()/close() edilir.
Videonun üzerinde canlı WYSIWYG altyazı katmanı (seçili font + boyut + dış kontur + gölge).
Trim tarzı 120Hz koyu kahverengi çubuk (üzerinde altyazı işaretleri) + düzenlenebilir anlık süre & aktif altyazı aralığı/toplam süre kutuları.
Sağdan sola sonsuz kaydırılabilen, ivmeli fling (Animatable.animateDecay) fizikli ve yalnızca görünür penceredeki kartları çizen (findVisibleCueRange + graphicsLayer { translationX }) sanallaştırılmış yatay altyazı şeridi; şerit ↔ çubuk ↔ süre kutusu ↔ video görüntüsü 4 yönlü senkronize çalışır.
Şeritte herhangi bir altyazıya dokununca hemen altında InlineSubtitleEditorCard açılır; yazılan metin ve başlangıç/bitiş zamanları videoda ve şeritte anlık güncellenir.
Harici dosya seçme (MainActivity.kt OpenDocument), videodaki gömülü metin altyazıyı tek tıkla alma (FfmpegEngine.extractEmbeddedSubtitleCues) ve sıfırdan satır ekleme (MainViewModel.kt) desteklenir.
Softsub / Hardsub & MediaCodec Turbo ("Acelem Var"):
ModeGridSection.kt içindeki eski EmbedSubtitlesPlaceholderRow kaldırılıp gerçek tıklanabilir ProcessingMode.EMBED_SUBTITLES satırı aktif edildi.
EncodingForegroundService.kt: EMBED_SUBTITLES için workDir içinde geçici .srt veya .ass + fonts/ hazırlayıp FFmpeg'e geçirir; CpuTopology.kt (ActivePerformanceStamps) ve özet şeritler güncellendi.
supportsMedia3HardwareTurbo() Hardsub + en az 1 altyazı varken true döner → işleme ekranında Acelem Var aktif olur.
SubtitleBitmapOverlay.kt & Media3TurboEngine.kt: OpenGL NDC alt-orta çapa (backgroundFrameAnchor=(0f, -0.89f), overlayFrameAnchor=(0f, -1f)), boş karelerde 1×1 şeffaf bitmap, aktif satırda önbellekli Bitmap (GPU texture yalnızca satır değişince yüklenir). Hardsub varken DirectMediaCodecTranscoder (Tier 2) atlanır ki altyazı sessizce düşmesin; yalnızca Media3 Transformer katmanları (OPTIMIZED_HW → CLEAN_MINIMAL_HW/SAFE_FPS_FALLBACK) kullanılır.
Dil & Testler:
AppLocalization.kt: TR/EN tüm 428 metin anahtarı 1:1 eşlendi.
SubtitleParserTest.kt ve FfmpegMathTest.kt: Tüm yeni kurallar için JVM birim testleri eklendi.
3. Karşılaşılan Hatalar, Kök Nedenleri ve Uygulanan Çözümler
Hata 1 — CI Yerel Derleme (fribidi · Nix glibc 2.40 vs Ubuntu glibc 2.39 çakışması)
Gelen Hata (30148ed sonrası Actions koşusu):
text
fribidi: failed
./gen-unicode-version: symbol lookup error: /nix/store/...-glibc-2.40-66/lib/libc.so.6:
undefined symbol: __nptl_change_stack_perm, version GLIBC_PRIVATE
Kök Neden:
Upstream arthenica/ffmpeg-kit-next'in flake.nix (android-r27d) ortamı Nix'in clang derleyicisini kuruyor ve scripts/function-android.sh içinde yalnızca HOST_CC değişkenini ayarlıyor; CC_FOR_BUILD / CC_BUILD / BUILD_CC tanımlamıyordu.
fribidi'nin ./configure betiği CC_FOR_BUILD boş olduğu için host derleyici olarak Ubuntu 24.04'ün /usr/bin/gcc'sini (glibc 2.39 dynamic linker) buldu; ancak Nix ortamındaki kütüphane yolları Nix'in glibc 2.40 libc.so.6 dosyasına bağlanınca ./gen-unicode-version çalıştırılırken çöktü.
Çözüm (Commit 29deba1):
patch_ffmpegkit_arm64_simd.py içinde set_toolchain_paths() sonrasına CC_FOR_BUILD="${HOST_CC}", BUILD_CC="${HOST_CC}", CC_BUILD="${HOST_CC}" (FreeType apinames için), CPP_FOR_BUILD="${HOST_CC} -E", CFLAGS_FOR_BUILD="", LDFLAGS_FOR_BUILD="" eklendi; ayrıca patch_fribidi_script() ile scripts/android/fribidi.sh ./configure satırına doğrudan CC_FOR_BUILD enjekte edildi.
Sonuç: fribidi, freetype, fontconfig, harfbuzz, libass ve tüm FFmpegKit Next (NDK r30 Clang 21) ARM64-O3 AAR derlemesi %100 başarıyla geçip önbelleğe alındı.
Hata 2 — Kotlin Derleme (compileDebugKotlin) — 2 Küçük Tip/Scope Hatası
Gelen Hatalar (29deba1 sonrası Actions koşusu):
FfmpegEngine.kt:1137: Argument type mismatch: actual type is 'Map<String, String>', but 'Map<String?, String?>?' was expected.
SubtitleStudioSection.kt:655: 'fun ColumnScope.AnimatedVisibility(...)' cannot be called in this context with an implicit receiver.
Kök Neden:
arthenica/ffmpeg-kit-next içinde FFmpegKitConfig.setFontDirectoryList(..., Map<String?, String?>?) imzası Kotlin Map<K, V>'nin K tip parametresinde invariant olması yüzünden Map<String, String> kabul etmiyordu.
SubtitleStudioSection.kt içinde bir Column'ın içindeki Box kapsamından doğrudan AnimatedVisibility(...) çağrılınca Kotlin ColumnScope.AnimatedVisibility eklentisini seçip örtük alıcı hatası verdi.
Çözüm (Commit a098d3b):
FfmpegEngine.kt: fontMap açıkça Map<String?, String?> olarak oluşturuldu.
SubtitleStudioSection.kt: androidx.compose.animation.AnimatedVisibility(...) şeklinde tam nitelikli çağrıldı.
4. En Son Nerede Kaldık / Sıradaki Kontrol Listesi
GitHub Actions (a098d3b): Önbellekteki v6 AAR ile testDebugUnitTest + assembleRelease + Release APK doğrulama kapısının yeşil geçmesi bekleniyor. Eğer yeni bir test veya R8/Release derleme hatası çıkarsa doğrudan hatayı buraya yapıştırıp çözebiliriz.
Gerçek Cihaz Testi (APK indirilince):
Altyazılı .mkv / .mp4 videoda ana karttaki .srt / .ass (ve çoklu izde üssel sayı) görünümü,
TRIM_VIDEO, REMUX_FORMAT (MP4/MKV/MOV/WEBM/TS/AVI/FLV/M4V/3GP) ve CRF modlarında Altyazıları Koru anahtarı,
Altyazı Göm stüdyosunda Media3 canlı sesli oynatma, uyumsuz videoda kare önizlemeye otomatik geçiş, sağdan sola sonsuz kayan altyazı şeridi ↔ çubuk ↔ süre ↔ video 4 yönlü senkronu, dokunarak satır içi düzenleme, 3 UTF-8 font/boyut ile hem Softsub hem Hardsub (libass + Acelem Var / MediaCodec Turbo) çıktıları,
Mevcut Termux karşılaştırmalı kodlama hızının birebir korunduğunun doğrulanması.
Dokümantasyon: Cihazda her şey onaylandıktan sonra yalnızca ilgili docs/*.md dosyalarına kısa ve nokta atışı ekleme yapılacak.