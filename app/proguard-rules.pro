# youtubedl-android parses yt-dlp's JSON with Jackson (reflection) and unzips its bundled
# Python with commons-compress, so keep both intact in release builds.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }

# Optional dependencies of the above that aren't on Android.
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.**

# Strip verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
