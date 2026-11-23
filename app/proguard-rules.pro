# FFLab ProGuard / R8 Rules

# Keep FFmpegKit & SmartException JNI classes and callbacks
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-dontwarn com.arthenica.smartexception.**
