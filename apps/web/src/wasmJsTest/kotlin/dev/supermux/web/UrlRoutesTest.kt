package dev.supermux.web

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.web.nav.UrlTarget
import dev.supermux.web.nav.parsePath
import dev.supermux.web.nav.pathFor
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlRoutesTest {
    @Test fun homeIsRoot() = assertEquals("/", pathFor(Route.Home, selectedId = null))
    @Test fun selectedSessionIsS() = assertEquals("/s/abc", pathFor(Route.Home, selectedId = "abc"))
    @Test fun launcherWithDraft() = assertEquals("/new?draft=d1", pathFor(Route.NewSession("d1"), null))
    @Test fun launcherNoDraft() = assertEquals("/new", pathFor(Route.NewSession(), null))
    @Test fun settingsSections() {
        assertEquals("/settings/voice", pathFor(Route.Settings(SettingsSection.Voice), null))
        assertEquals("/settings/git-hosting", pathFor(Route.Settings(SettingsSection.GitHosting), null))
        assertEquals("/settings/editor", pathFor(Route.Settings(SettingsSection.EditorLsp), null))
        assertEquals("/personal-assistants", pathFor(Route.Settings(SettingsSection.PersonalAssistants), null))
    }
    @Test fun topLevels() {
        assertEquals("/usage", pathFor(Route.Usage, null)); assertEquals("/devices", pathFor(Route.Devices, null))
        assertEquals("/archived", pathFor(Route.Archived, null)); assertEquals("/proxies", pathFor(Route.Proxies, null))
        assertEquals("/displays", pathFor(Route.Displays, null)); assertEquals("/settings/appearance", pathFor(Route.Appearance, null))
        assertEquals("/settings/updates", pathFor(Route.AppUpdate, null)); assertEquals("/add-host", pathFor(Route.AddHost, null))
    }
    @Test fun parseRoundTrips() {
        val cases = listOf(Route.Home, Route.NewSession("d1"), Route.Settings(SettingsSection.Curator), Route.Usage, Route.Devices,
            Route.Archived, Route.Proxies, Route.Displays, Route.Appearance, Route.AppUpdate, Route.AddHost,
            Route.Settings(SettingsSection.PersonalAssistants))
        for (r in cases) assertEquals(UrlTarget.Screen(r), parsePath(pathFor(r, null)), r.toString())
        assertEquals(UrlTarget.Session("abc"), parsePath("/s/abc"))
    }
    @Test fun unknownAndLegacyPathsGoHome() {
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/nope/what"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/setup"))              // wizard arrives in plan 4
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Agents)), parsePath("/settings"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/settings/keyboard")) // dropped page
    }
}
