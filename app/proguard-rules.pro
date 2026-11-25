# FFmpeg Compressor & Editor — R8 / ProGuard Rules

# Keep FFmpegKit & SmartException JNI classes and native callbacks
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-dontwarn com.arthenica.smartexception.**

# Keep custom JNI bridge matching exact C symbols in app/src/main/cpp/cpu_affinity.c
-keep class com.example.service.NativeCpuAffinity {
    *;
}

# Keep Media3 & Throwable class names readable for runtime compatibility checks and AI error reports
-keepattributes SourceFile,LineNumberTable
-keepnames class * extends java.lang.Throwable
-dontwarn androidx.media3.**
