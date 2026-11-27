Hata düzeltildi.

Kök Neden

Android NDK başlıklarında (<malloc.h>) mallopt işlevi yalnızca __ANDROID_API__ >= 26 için tanımlıdır; projemiz minSdk = 24 hedeflediği için CMake android24 sysroot’u ile derlerken call to undeclared function 'mallopt' hatası veriyordu.

Düzeltme
cpu_affinity.c: mallopt doğrudan çağrılmak yerine pthread_once + dlsym(RTLD_DEFAULT, "mallopt") ile çalışma zamanında çözülüyor (minSdk = 24 ile tüm 4 ABI’da uyarısız derlenir; Android 8–16 cihazlarda Scudo/Bionic mallopt tam aktif çalışır, API 24–25’te güvenle false döner).
CMakeLists.txt: target_link_libraries(cpu_affinity PRIVATE dl) eklendi.

Tekrar push edip CI derlemesini tetikleyebilirsiniz.