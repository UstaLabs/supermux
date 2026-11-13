package dev.supermux.state

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsKeysTest {
    @Test fun draftKeyIsScopedBySession() { assertEquals("draft:abc", SettingsKeys.draft("abc")) }
    @Test fun launcherDraftIsASingleGlobalKey() { assertEquals("launcher:draft", SettingsKeys.LAUNCHER_DRAFT) }
    @Test fun launcherPrefsIsASingleGlobalKey() { assertEquals("launcher:prefs", SettingsKeys.LAUNCHER_PREFS) }

    /** Both apps' session lists collapse the same groups under ONE key (cluster F1). */
    @Test fun collapsedProjectPathsIsOneKeyForBothApps() {
        assertEquals("sessionList:collapsedPaths", SettingsKeys.SESSION_LIST_COLLAPSED_PATHS)
    }

    /**
     * #97: the voice keys (`voice:stt`/`cleanup`/`tts`) are gone with the old Voice screen; the dynamic-colour key
     * stays only because Android's one-time appearance migration still carries an old choice into it.
     */
    @Test fun dynamicColorStaysForAndroidsMigration() { assertEquals("appearance:dynamicColor", SettingsKeys.DYNAMIC_COLOR) }
}
