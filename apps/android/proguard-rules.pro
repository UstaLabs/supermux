# Supermux Android — R8/ProGuard keep rules for the release build.
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
# termlib (terminal emulator) — keep public surface to be safe
-keep class com.termux.** { *; }
-dontwarn com.termux.**

# ---- App entrypoints (Activities/Application referenced from the manifest are
#       kept by the AGP-generated rules; this is belt-and-suspenders) ----
-keep class dev.supermux.android.MainActivity { *; }

# ---- Google Tink (behind EncryptedSharedPreferences / SecureTokenStore) refs
#       compile-only javax.annotation.* that isn't on the runtime classpath ----
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
