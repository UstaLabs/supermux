# Supermux Android — R8/ProGuard keep rules.
#
# NOTE: release minification is currently DISABLED (apps/android/build.gradle.kts
# isMinifyEnabled=false) — these rules are INERT today. They are kept, corrected and
# documented for if/when minify is re-enabled. The v1 minified release (commit f7b8d25)
# crashed/misbehaved because these were incomplete: R8 renames code that JNI / WebView /
# Tink resolve BY NAME. Every rule below marked "[required]" fixed a real runtime break:
#  - org.connectbot.terminal.**  [required] native crash opening Terminal (TerminalNative JNI)
#  - @JavascriptInterface methods [required] cm6 editor/LSP bridge calls silently no-op
#  - com.google.crypto.tink.**    [required] EncryptedSharedPreferences lost the pairing
# If re-enabling minify, re-verify EVERY subsystem (terminal, voice, editor/LSP, VNC/scrcpy,
# QR pairing, chat) on the minified build — static analysis cannot catch these.
#
# Goal: a correct minified build over a maximally-shrunk one — KMP + Ktor +
# kotlinx.serialization + Compose rely on reflection/generated code that R8 can
# strip without these keeps.

# ---- Attributes ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations

# ---- kotlinx.serialization ----
# (the runtime ships consumer rules, but keep the app/shared @Serializable model
#  + its generated serializers explicitly — ServerFrame/ClientFrame/LogEntry/etc.)
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.supermux.**$$serializer { *; }
-keepclassmembers class dev.supermux.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.supermux.** {
    @kotlinx.serialization.Serializable <methods>;
}
-keep @kotlinx.serialization.Serializable class dev.supermux.** { *; }

# ---- Ktor (CIO client + websockets) ----
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**

# ---- Kotlin reflection / metadata ----
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ---- Third-party native/UI libs ----
-keep class com.journeyapps.barcodescanner.** { *; }   # zxing-android-embedded
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
# termlib (terminal emulator) — [required] the native lib (libjni_cb_term.so) resolves
# TerminalNative + the TerminalCallbacks methods/fields BY NAME via JNI; renaming any of
# them aborts in Terminal::Terminal on open. Coordinate is org.connectbot:termlib.
-keep class org.connectbot.terminal.** { *; }
-keepclassmembers class org.connectbot.terminal.** { *; }
-dontwarn org.connectbot.terminal.**

# ---- App entrypoints (Activities/Application referenced from the manifest are
#       kept by the AGP-generated rules; this is belt-and-suspenders) ----
-keep class dev.supermux.android.MainActivity { *; }

# ---- WebView JS bridge — [required] cm6 editor + LSP shim ----
# R8 renames @JavascriptInterface methods (EditorEngine onChange/onSave/onReady/lspOut)
# unless kept, so the WebView's JS calls into them silently no-op.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Google Tink + EncryptedSharedPreferences (SecureTokenStore) — [required] ----
# Tink loads keyset managers/primitives reflectively; renaming them breaks the encrypted
# token store (pairing didn't survive a restart). Keep Tink + androidx.security; the
# compile-only javax.annotation.* it references isn't on the runtime classpath.
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
