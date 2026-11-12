# ═══════════════════════════════════════════════════════════════
#  PlugX IPTV — ProGuard / R8 Rules (Release hardening)
# ═══════════════════════════════════════════════════════════════

# ── Keep application entry points ──────────────────────────────
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep BuildConfig for version checks
-keep class app.plugxaura.iptv.BuildConfig { *; }

# ── Keep AndroidX / Support library internals ───────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**

# ── Keep Media3 / ExoPlayer (native JNI bindings) ──────────────
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn com.google.android.exoplayer2.**
-dontwarn androidx.media3.**

# ── Keep OkHttp / Okio (reflection used internally) ────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ── Keep Gson serialization ─────────────────────────────────────
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# Preserve JsonObject / JsonArray structure
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Keep crypto/security classes intact ─────────────────────────
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# ── Remove unused code aggressively ─────────────────────────────
-dontnote **
-dontwarn **

# ── Prevent reflection-based extraction ─────────────────────────
# Strip source file names and line numbers from stack traces
-renamesourcefileattribute X
-keepattributes SourceFile,LineNumberTable

# Keep exception types visible (for crash reporting)
-keepattributes Exceptions

# ── Anti-reverse-engineering extras ────────────────────────────
# Remove inner class attribute (hides class structure)
-keepattributes !InnerClasses,!EnclosingMethod
