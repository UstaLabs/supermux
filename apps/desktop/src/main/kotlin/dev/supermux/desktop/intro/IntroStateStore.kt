// First-run-intro "seen" persistence: a plain marker file (`intro-seen`, contents = intro
// version) next to ui-state.json. Not a secret and not state worth serializing — exists() is
// the whole API. Bumping INTRO_VERSION re-shows a redesigned intro to existing users (the old
// marker holds a lower version); the marker write is best-effort, a failed write just means
// the intro plays once more next launch.
package dev.supermux.desktop.intro

import dev.supermux.desktop.auth.DesktopTokenStore
import java.nio.file.Files
import java.nio.file.Path

class IntroStateStore(val path: Path = defaultPath()) {
    fun seen(): Boolean =
        runCatching { Files.readString(path).trim().toIntOrNull() ?: 0 }
            .getOrDefault(0) >= INTRO_VERSION

    fun markSeen() {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, INTRO_VERSION.toString())
        }
    }

    companion object {
        const val INTRO_VERSION = 1

        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("intro-seen")

        /**
         * Show policy (evaluated once at startup):
         * - SM_INTRO=1 → always show (screenshot/dev runs; the seen flag is NOT persisted, so a
         *   forced run never consumes a real user's one viewing).
         * - SM_INTRO=0 → never show.
         * - SM_PAIR_TOKEN set → never show: that's a seeded dev/CI run (the pairing seed bypasses
         *   onboarding), and the intro overlay would hijack every headless verification screenshot.
         * - otherwise → show exactly once, until [markSeen].
         */
        fun shouldShow(envIntro: String?, envPairToken: String?, store: IntroStateStore): Boolean = when {
            envIntro == "1" -> true
            envIntro == "0" -> false
            !envPairToken.isNullOrBlank() -> false
            else -> !store.seen()
        }
    }
}
