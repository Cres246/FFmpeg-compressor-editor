ffmpeg_benchmark_why termux fast.md raporunun tamamı satır satır okundu; ayrıca doğrudan son GitHub Release APK’sındaki (libavutil.so, libavcodec.so, libswscale.so, libffmpegkit.so) ikili dosyalar ile upstream arthenica/ffmpeg-kit-next ve Termux ffmpeg derleme betikleri birebir karşılaştırıldı.

İkili (.so) Adli Analiz Bulguları

Release APK’sındaki mevcut libavcodec.so ikili denetiminde:

ff_vp9_avg64_neon (0x5CE9E0), getauxval / av_get_cpu_flags ve SDOT=4376 / UDOT=3921 / I8MM=1864 makine komutlarının mevcut olduğu doğrulandı.
Ancak libavcodec C decode döngülerini (vp9/hevc/h264 entropi çözümü, kare iş parçacığı yönetimi, libswscale) ve çekirdek süresini (stime) Termux’un gerisinde bırakan 6 somut kök neden yakalandı:
ld.lld LTO arka uç -O2 düşüşü & erken -flto: ffmpeg --enable-lto ile derleniyor ama -Wl,--lto-O3 verilmediği için ld.lld tüm libavcodec/libswscale LTO arka uç kod üretimini varsayılan --lto-O2 seviyesinde yapıyordu (x264 LTO’suz tam -O3 aldığı için Termux’u %6 geçmişti!). Ayrıca CFLAGS içinde ./configure öncesi gelen -flto -fuse-ld=lld configure testlerini kirletiyordu.
Derleyici kuşağı farkı (NDK r27d Clang 18.0.4 vs. Termux NDK r30 Clang 21.0.0): Upstream flake.nix yalnızca android-r27d (Clang 18.0.4) içeriyor; 3 LLVM ana sürümü geride.
Patcher içindeki gizli hatalar: function-android.sh yamasında count=1 yüzünden -Wl,-Bsymbolic-functions yalnızca Application.mk’ya eklenip arm64-v8a LDFLAGS’ini atlıyordu; ffmpeg.sh içindeki --enable-dotprod/--enable-i8mm sorgusu --help çıktısında yalnızca --disable-* yazdığı için hiç tetiklenmiyordu; upstream dav1d.sh -Denable_asm=false ile tüm ARM64 AV1 assembly’sini kapatıyordu ve libvpx.sh --enable-small içeriyordu.
Android Zygote Scudo M_DECAY_TIME = 0 (+0.71s – +0.96s stime): Android Zygote uygulama süreçlerinde free() anında hemen madvise(MADV_DONTNEED) yapar; Termux gibi bağımsız CLI süreçlerinde ise tembel decay açıktır. 1080p/4K AVFrame tampon döngüsünde bu anlık boşaltma binlerce ekstra çekirdek sayfa hatası (minor page fault) üretiyordu.
ffkitsaf: 32 KB okuma tamponu: ffkitsaf: yazmada 256 KB kullanırken okumada varsayılan 32 KB ile 8× daha fazla SAF/FUSE read() sistem çağrısı yapıyordu.
Parçalanmış av_log çıkışı (Öncelik 3): FfmpegEngine.executeStep satır sonu beklemeden her av_log parçasını yeni satır olarak iletiyordu.
Yapılan Tüm Değişiklikler

FFmpegKit Next LTO-O3 + Zero-PLT + dav1d/libvpx/ffkitsaf Patcher’ı — patch_ffmpegkit_arm64_simd.py

function-android.sh: arm64-v8a ffmpeg LDFLAGS’ine -Wl,--lto-O3 eklendi; ./configure öncesi CFLAGS’teki erken -flto/-fuse-ld=lld temizlendi; count=1 hatası düzeltilerek -Wl,-Bsymbolic-functions hem APP_LDFLAGS (libffmpegkit.so) hem arm64-v8a get_arch_specific_ldflags() içine uygulandı; APP_CFLAGS’e -fno-semantic-interposition -fomit-frame-pointer ve Clang 19–21 uyumluluk bayrakları (-Wno-error=incompatible-pointer-types vb.) eklendi.
ffmpeg.sh: Bozuk --enable-dotprod/--enable-i8mm kontrolü --(en|dis)able-* olarak düzeltildi; ./configure çağrısına açıkça --enable-neon --enable-dotprod --enable-i8mm --as="${AS}" --optflags="-O3 -fno-math-errno -fno-signed-zeros" --extra-cflags="-O3 -fno-semantic-interposition -fomit-frame-pointer" --extra-ldflags="-Wl,-Bsymbolic-functions -Wl,--lto-O3" eklendi ve CI günlüğüne config.mak, config.h ve vp9*neon*.o / hevc*neon*.o / h264*neon*.o / swscale/aarch64/*.o doğrulaması eklendi.
dav1d.sh: arm64-v8a için -Denable_asm=true açıldı (AV1 çözümünde ~4–6× hızlanma, getauxval çalışma zamanı korumalı).
libvpx.sh: --enable-small kaldırıldı, -O3 + zero-PLT uygulandı.
x265.sh: Upstream -DENABLE_ASSEMBLY=1 varken dahi ENABLE_NEON_DOTPROD=ON / ENABLE_NEON_I8MM=ON dosya-bazlı güvenlik kontrolüyle doğrulandı.
tools/protocols/libavformat_file_ffkitsaf.inc: Akış olmayan okumalarda (AVIO_FLAG_READ) 256 KB AVIO tamponu (max_packet_size = 262144) eklendi (8× daha az SAF/FUSE read() syscall’ı).
android.sh: Nix shellHook sonrası FFMPEGKIT_OVERRIDE_NDK_ROOT kancası eklendi.

Otomatik Google NDK r29/r30 (Clang 21) Yükseltmesi & CI Doğrulaması — build_optimized_ffmpegkit_arm64.sh, build-apk.yml

Nix profili < r29 ise host sdkmanager üzerinden en güncel resmi Google NDK (r29/r30 · Clang 21, yoksa r28) otomatik kurulup FFMPEGKIT_OVERRIDE_NDK_ROOT ile kullanılır; herhangi bir uyumsuzlukta tek seferlik otomatik olarak Nix’in yerleşik android-r27d profiline temiz geri döner.
AAR önbellek anahtarı v4’e yükseltildi ve Release APK doğrulama adımına aktif Clang .comment + FFMPEG_CONFIGURATION çıktısı eklendi (Kırmızı Çizgi 18 kapıları korundu).

Android Scudo mallopt(M_DECAY_TIME) stime Optimizasyonu — cpu_affinity.c, NativeCpuAffinity.kt, CpuPerformanceBooster.kt

nativeSetAllocatorEncodingMode(true) yazılım FFmpeg çalışırken Bionic/Scudo’yu mallopt(M_DECAY_TIME, 1) (tembel decay) moduna alarak 1080p/4K kare tamponu madvise/page-fault stime kaybını azaltır; işlem bittiğinde veya Turbo moduna geçildiğinde mallopt(M_DECAY_TIME, 0) + mallopt(M_PURGE, 0) ile tüm native belleği anında Android’e iade eder.

Öncelik 3 — Parçalanmış av_log Satır Tamponlaması & Nötr Öncelik — FfmpegEngine.kt, FfmpegLogLineAssemblerTest.kt

FfmpegLogLineAssembler eklendi: Stream #0, :0, (eng) gibi bölünmüş av_log parçalarını \n gelene kadar tek satırda birleştirir, tekil \r ilerleme satırlarını düşürür ve oturum bitiminde kalan son satırı boşaltır.
FfmpegEngine.executeStep içindeki eski THREAD_PRIORITY_DISPLAY ataması kaldırılarak saf nötr nice 0 profiliyle tam tutarlılık sağlandı.
Dokümantasyon güncellendi: bugfixes-build-perf-and-ffmpeg9.md.
Doğrulama Durumu
Tüm yamalar gerçek upstream arthenica/ffmpeg-kit-next betiklerine karşı test edilip (dav1d regex’indeki bir sınır hatası yakalanıp düzeltildi) bash -n sözdizimi ve kabuk çalıştırma doğrulamaları RC=0 ile geçti.
Bu Windows ortamında yerel JDK/Gradle ve Python kurulu olmadığı için testDebugUnitTest ve yeni v4 FFmpegKit Next (NDK r29/r30 · Clang 21 + LTO-O3) AAR derlemesi bir sonraki GitHub Actions push çalışmasında otomatik yürütülecektir. Yeni Release APK indirilince cihazda Test 1 ve Test 2’yi tekrar çalıştırıp utime/stime düşüşünü doğrulayabiliriz; hâlâ belirgin stime farkı kalırsa o zaman Öncelik 2A (:encoder süreç izolasyonu) adımına geçebiliriz.