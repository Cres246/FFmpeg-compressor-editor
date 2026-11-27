# FFmpeg Compressor & Editor — Yapay Zeka Ajanı Geliştirme ve Devir Teslim Kılavuzu (`AGENTS.md`)

Bu doküman, **`Cres246/FFmpeg-compressor-editor`** projesini devralan yapay zeka ajanlarının önceki geliştirme sürecindeki tüm mimari kararları, FFmpeg/Media3 kurallarını, çözülen hataları, kullanıcı arayüzü tercihlerini, kesinlikle yapılmaması gerekenleri ve sıradaki adımları eksiksiz şekilde okuyup kaldığı yerden devam edebilmesi için hazırlanmıştır.

---

## Çalışma Kuralı (Modüler Dokümantasyon)

Bir göreve başlamadan önce aşağıdaki indeksi kontrol et. YALNIZCA görevin doğrudan dokunduğu alan(lar)ın ilgili dosyasını baştan sona oku; görevinle ilgisiz dosyaları okuyarak gereksiz bağlam/kota tüketme.

Her ara adımda doküman güncelleme; yalnızca özellik/bugfix tamamlanıp onaylandığında, sadece ilgili tek docs/*.md dosyasına kısa ve nokta atışı ekleme yap, diğer dokümanları okuma

## Dokümantasyon İndeksi

| Dosya Referansı | Ne Zaman Okunması Zorunlu? (Tetikleyici / Klasör / Anahtar Kelimeler) | İçerdiği Başlıklar |
| :--- | :--- | :--- |
| @docs/overview-and-architecture.md | Proje yapısı, 11 işleme modu, 4 katmanlı paket mimarisi (`domain/`, `data/`, `service/`, `ui/`), `saf:` sıfır kopya, `EncodingForegroundService`, `BackgroundExecutionGuard`, `CpuPerformanceBooster`, `EncodingJobController`, Magic Capsule, `MediaModels.kt` | 1. Proje Kimliği, Kökeni ve Mevcut Durum (1.1–1.3); 2. Mimari Kararlar (2.1–2.4) |
| @docs/ffmpeg-rules.md | `FfmpegMath.kt`, `FfmpegEngine.kt`, `CustomFfmpegParser.kt`, 2-Pass ABR bit hızı, uyarlanabilir stres katmanları, CRF, `hvc1`, 3 yönlü ölçekleme (`EVEN_VF`), keyframe tarama, kayıpsız kesim (`.mkv`/`.mp4`), ses bit hızı tavanı, özel FFmpeg/`ffprobe` komutları | 3. FFmpeg Kuralları ve Değişmezleri (Invariants) |
| @docs/turbo-mediacodec-rules.md | `Media3TurboEngine.kt`, `DirectMediaCodecTranscoder.kt`, `CleanHardwareEncoderFactory`, Turbo Modu, Media3 Transformer, HW VPU el değiştirme/soğuma, HDR→SDR koruması, ön-remux geri dönüşü | 4. Media3 Transformer & `DirectMediaCodecTranscoder` ("Turbo Modu") Kuralları |
| @docs/bugfixes-core-and-media.md | SAF FD, GOP geri sıçraması, ART `VerifyError`, kapak resmi, `c2.qti` VPU, API 24–28 `FileProvider`, `DirectMediaCodecTranscoder` bugfixleri, `VideoFrameScrubber`, `CustomFfmpegParser`, MagicOS arka plan dondurma, 4K HEVC 10-bit düşüşü geçmişi | 5. Çözülen Kritik Buglar (Çekirdek, Medya, Trim & Arka Plan) |
| @docs/bugfixes-build-perf-and-ffmpeg9.md | `app/build.gradle.kts`, `.github/workflows/`, `scripts/`, R8/ProGuard, JNI (`cpu_affinity.c`), `arthenica/ffmpeg-kit-next` (FFmpeg 9.0.x · NDK r30 Clang 21 · LTO-O3 · Zero-PLT), Scudo `M_DECAY_TIME`, 256 KB `ffkitsaf:`, Termux hız eşitliği/üstünlüğü, geri alınan deneyler (`*_mediacodec`, `LocalInputStager`) | 5 (Devam). Çözülen Kritik Buglar (Derleme, Performans, FFmpeg 9, v4 Benchmark & Geri Alınan Deneyler) |
| @docs/ffmpeg_benchmark_why termux fast.md | Termux vs. Uygulama hız kıyaslaması, `-benchmark` (`utime`/`stime`/`rtime`/`maxrss`) analizi, v4 öncesi (`-15.6%`) vs. v4 sonrası (tüm CRF ve 2-Pass kodlamalarda `+1.3%`–`+6.8%` Termux üstünlüğü, `VP9 analiz -11.2%` kök nedeni ve `:encoder`/`ProcessBuilder` kapanış kararı) | Benchmark Raporu (Bölüm 1–5: Kök Neden; Bölüm 6: v4 Doğrulama & Kapanış) |
| @docs/ui-ux-guidelines.md | `ui/`, `ui/theme/`, `ui/components/`, `ui/screens/`, `MainActivity.kt`, `MainViewModel.kt`, `AppLocalization.kt`, Cream & Cello renk paleti, 4 fontlu tipografi, 120Hz animasyonlar, `AppStrings`, ekran ve bileşen kuralları | 6. Kullanıcı Arayüzü (UI/UX) Tercihleri |
| @docs/redlines-and-roadmap.md | Herhangi bir mimari/motor değişikliği öncesi tüm 20 kırmızı çizginin tam listesini doğrulamak veya güncel yol haritasını (P0–P7, Sıradaki 1–3) görmek gerektiğinde | 7. Kesinlikle Yapılmaması Gerekenler (Kırmızı Çizgiler); 8. Sıradaki Yapılacaklar (Güncel Yol Haritası) |

---

## Global Temel Kurallar (Her Turda Geçerli Kırmızı Çizgiler)

Aşağıdaki kurallar orijinal kırmızı çizgiler listesinden birebir alınmıştır ve görev alanından bağımsız olarak her turda zorunludur (tüm 20 kural ve yol haritası için: @docs/redlines-and-roadmap.md):

1. **`AppStrings` veya büyük yapılandırma sınıflarını ASLA `data class` yapmayın** (`>255` kurucu parametresi ART `VerifyError` üretir).
2. **Girdi videolarını `cacheDir`'e ASLA kopyalamayın (önbelleğe alma özelliği kesinlikle eklenmeyecek — gerçek Honor UFS cihazda 5 GB 4K filmle test edilip hıza %0 etkisi olduğu kanıtlanmıştır; saf sıfır-kopya `saf:` daima tercih edilir)** ve `FFmpegKitConfig.getSafParameterForRead` token'ını birden fazla adımda ASLA yeniden kullanmayın.
10. **Compose gövdesinde ASLA `mutableStateOf` mutasyonu, Ana İş Parçacığı dosya I/O'su (`file.length()`), her yüzde adımında Binder IPC (`canPostPromotedNotifications()`) veya 120Hz animasyon state delegasyonu (`by animateFloatAsState`) yapmayın.**
11. **Coroutine `catch (t: Throwable)` bloklarında `CancellationException`'ı ASLA yutmayın.**
13. **[`app/build.gradle.kts`](app/build.gradle.kts) içindeki `signingConfigs` (`release` ve `debugConfig`) bloklarını ASLA silmeyin veya değiştirmeyin** (AI Studio için zorunlu).
14. **H.264 10-bit seçeneğini UI'da tekrar açmayın;** metinleri `stringResource()` yerine `AppStrings` üzerinden verin; Magic Capsule `shortCriticalText`'i 4 karakterin üzerine çıkarmayın.
18. **RELEASE APK SADECE `arthenica/ffmpeg-kit-next` (FFmpeg 9.0.2+) İLE ÇALIŞMAK ZORUNDADIR — FFmpeg 8 (`8.1.9` / `n8.1.x`) KALINTISI KESİNLİKLE YASAKTIR:**
    * GitHub Actions Release APK hattında `arthenica/ffmpeg-kit-next` derleme adımına ASLA `continue-on-error: true` eklemeyin, `-PrequireCustomFfmpeg=true --no-configuration-cache` bayraklarını ve `Release APK Motor ve SIMD Dogrulamasi` ikili denetim kapısını ASLA kaldırmayın.
    * `app/build.gradle.kts` içindeki `else { implementation(libs.ffmpeg.kit.full.gpl) }` (`8.1.9`) dalı **YALNIZCA** Nix bulunmayan AI Studio bulut önizleme derlemesi kırılmasın diye vardır; GitHub Releases'ta üretilen gerçek Release APK'sına `useCustomSimdFfmpeg == true` + R8 dead-code elimination nedeniyle FFmpeg 8'den tek bir `.so`, `.class` veya kaynak dosyası dahi **ASLA** girmez. İki FFmpeg aynı APK içine kesinlikle konulamaz.
19. **`com.arthenica.ffmpegkit.*` sınıflarında (`MediaInformationSession`, `MediaInformation`, `StreamInformation`, `AbstractSession`, `FFmpegSession`, `FFprobeSession`) ASLA Kotlin sentetik JavaBean özellik sözdizimi (`.returnCode`, `.sessionId`, `.mediaInformation`, `.streams`, `.allProperties`, `.allLogsAsString`, `.output`, `.type`, `.codec`, `.width`, `.height`, `.duration`, `.size`, `.bitrate`) kullanmayın;** `arthenica/ffmpeg-kit-next` bu sınıfları `private`/`protected` alanlı Kotlin sınıfları olarak derlediği için her zaman açık `.getXxx()` metotlarını çağırın (`ReturnCode.value`, `Log.message` ve `Statistics.time/speed/size` açık Kotlin özellikleridir).
20. **PERFORMANS KORUMA VE ÖN ONAY KURALI (TERMUX ÜSTÜNLÜĞÜ KIRMIZI ÇİZGİSİ):** Kodlama veya çözme hızını düşürme ihtimali bulunan **en basit veya en küçük değişikliklerde dahi** (sürekli durum kontrolü/polling, per-frame/per-callback ek loglama veya JNI çağrısı, gereksiz Compose recomposition/animasyon, ek dosya kopyalama/I/O, thread önceliği/allocator/FFmpeg bayrak müdahalesi vb.) uygulamadan **önce mutlaka kullanıcıyı açıkça uyar, olası performans etkisini belirt ve açık iznini al.** Termux hızını yakalamak ve geçmek için verilen emek titizlikle korunacaktır; SIMD/`-O3`/`LTO-O3`/`Scudo`/`256K SAF` gibi çalışma zamanı göstergeleri yalnızca işlem başlangıcında **tek seferlik (zero-polling)** hesaplanır ve işlem süresince bir daha asla kontrol edilmez.
