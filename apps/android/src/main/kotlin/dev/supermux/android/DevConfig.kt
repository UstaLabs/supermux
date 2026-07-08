package dev.supermux.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.supermux.auth.SecureTokenStore

object DevConfig {
    const val HOME = "/home/user"

    /**
     * On-device speech-to-text (live transcript) toggle. ON to mirror iOS, which tries the
     * platform on-device recognizer FIRST and only records-and-uploads audio as a fallback — so
     * dictation produces text even when the broker has no whisper pipeline (ffmpeg + whisper-cli)
     * installed, which is the default.
     *
     * When on, [dev.supermux.android.chat.DictationEngine] supplies the transcript locally and the
     * whisper POST is reduced to an OPTIONAL cleanup pass over that draft (the JSON `draft` branch of
     * /transcribe, no ffmpeg needed); if cleanup fails the raw on-device draft is kept
     * ([dev.supermux.android.chat.DictationController.runTranscription] rawFallback), exactly as iOS
     * does. On-device recognition is OEM/locale-gated: where it isn't available the controller falls
     * back to the audio-upload → whisper path exactly as before, so enabling this never regresses a
     * device — it only lights up the ones that support it.
     */
    const val ENABLE_ONDEVICE_STT = true

    private const val PHYSICAL_BROKER = "ws://CHANGE_ME:9898"  // set to your broker host
    private const val EMULATOR_BROKER = "ws://10.0.2.2:9898"

    /** Debug fallback when [SecureTokenStore] is empty (debug builds only). */
    const val DEBUG_TOKEN = ""  // debug-only fallback; set a dev token or leave empty

    fun brokerUrl(): String = if (isEmulator()) EMULATOR_BROKER else PHYSICAL_BROKER

    /**
     * Debug-only convenience: if this is a debuggable build, a [DEBUG_TOKEN] is set, and the
     * [SecureTokenStore] is empty, seed BOTH the token and the broker base URL so a dev build
     * boots straight past the pairing gate (the already-paired emulator keeps working).
     *
     * This is NOT the production pairing path — the MainActivity gate + onboarding flow own
     * that now. On release builds, or when [DEBUG_TOKEN] is empty, this is a no-op and the
     * gate shows [dev.supermux.android.pairing.OnboardingScreen].
     */
    fun seedDebugPairingIfEmpty(context: Context) {
        if (DEBUG_TOKEN.isEmpty()) return
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val store = SecureTokenStore()
        if (store.load().isNullOrBlank()) {
            store.save(DEBUG_TOKEN)
            store.saveBaseUrl(brokerUrl())
        } else if (store.loadBaseUrl().isNullOrBlank()) {
            // Token present from an earlier build that predates base-url persistence — backfill it.
            store.saveBaseUrl(brokerUrl())
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk_gphone")
    }
}
