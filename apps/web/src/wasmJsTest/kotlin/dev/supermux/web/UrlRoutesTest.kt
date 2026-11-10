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
        assertEquals("/settings", pathFor(Route.Settings(), null))
        assertEquals("/settings/general", pathFor(Route.Settings(SettingsSection.General), null))
        assertEquals("/settings/providers", pathFor(Route.Settings(SettingsSection.Providers), null))
        assertEquals("/settings/voice", pathFor(Route.Settings(SettingsSection.Voice), null))
        assertEquals("/settings/coding", pathFor(Route.Settings(SettingsSection.Coding), null))
        assertEquals("/settings/memory", pathFor(Route.Settings(SettingsSection.Memory), null))
        assertEquals("/settings/privacy", pathFor(Route.Settings(SettingsSection.PrivacyLabs), null))
        assertEquals("/settings/machine", pathFor(Route.Settings(SettingsSection.Machine), null))
    }
    @Test fun subPagesHaveTheirOwnPathAndAnchorsHaveNone() {
        assertEquals("/settings/providers/claude", pathFor(Route.Settings(SettingsSection.Providers, sub = "claude"), null))
        assertEquals("/settings/voice/glossary", pathFor(Route.Settings(SettingsSection.Voice, sub = "glossary"), null))
        assertEquals("/settings/machine/links", pathFor(Route.Settings(SettingsSection.Machine, sub = "links"), null))
        // A flash is a one-off: the anchor is never in the URL.
        assertEquals("/settings/coding", pathFor(Route.Settings(SettingsSection.Coding, anchor = "github"), null))
    }
    @Test fun everyNewSlugRoundTrips() {
        val cases = SettingsSection.entries.map { Route.Settings(it) } + listOf(
            Route.Settings(),
            Route.Settings(SettingsSection.Providers, sub = "codex"),
            Route.Settings(SettingsSection.Voice, sub = "glossary"),
            Route.Settings(SettingsSection.Machine, sub = "status"),
            Route.Settings(SettingsSection.Machine, sub = "computer"),
            Route.Settings(SettingsSection.Machine, sub = "devices"),
            Route.Settings(SettingsSection.Machine, sub = "links"),
            Route.Settings(SettingsSection.Machine, sub = "worktrees"),
        )
        for (r in cases) assertEquals(UrlTarget.Screen(r), parsePath(pathFor(r, null)), r.toString())
    }
    @Test fun aSubPageOnlyParsesWhereThatPageHasIt() {
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Providers, sub = "claude")), parsePath("/settings/providers/claude"))
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Providers)), parsePath("/settings/providers/glossary"))
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Voice)), parsePath("/settings/voice/claude"))
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Machine)), parsePath("/settings/machine/nope"))
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.General)), parsePath("/settings/general/theme"))
    }
    @Test fun everyOldSlugLandsOnItsNewPlace() {
        val old = mapOf(
            "/settings/agents" to Route.Settings(SettingsSection.Providers),
            "/settings/accounts" to Route.Settings(SettingsSection.Providers),
            "/settings/curator" to Route.Settings(SettingsSection.Memory),
            "/settings/editor" to Route.Settings(SettingsSection.Coding, anchor = "code-intelligence"),
            "/settings/git-hosting" to Route.Settings(SettingsSection.Coding, anchor = "github"),
            "/settings/system" to Route.Settings(SettingsSection.Machine, sub = "status"),
            "/settings/devices" to Route.Settings(SettingsSection.Machine, sub = "devices"),
            "/settings/proxies" to Route.Settings(SettingsSection.Machine, sub = "links"),
            "/settings/worktrees" to Route.Settings(SettingsSection.Machine, sub = "worktrees"),
        )
        for ((path, route) in old) assertEquals(UrlTarget.Screen(route), parsePath(path), path)
    }
    @Test fun topLevels() {
        assertEquals("/usage", pathFor(Route.Usage, null)); assertEquals("/devices", pathFor(Route.Devices, null))
        assertEquals("/archived", pathFor(Route.Archived, null)); assertEquals("/proxies", pathFor(Route.Proxies, null))
        assertEquals("/displays", pathFor(Route.Displays, null)); assertEquals("/settings/appearance", pathFor(Route.Appearance, null))
        assertEquals("/settings/updates", pathFor(Route.AppUpdate, null)); assertEquals("/add-host", pathFor(Route.AddHost, null))
    }
    @Test fun parseRoundTrips() {
        val cases = listOf(Route.Home, Route.NewSession("d1"), Route.Settings(SettingsSection.Memory), Route.Usage, Route.Devices,
            Route.Archived, Route.Proxies, Route.Displays, Route.Appearance, Route.AppUpdate, Route.AddHost,
            Route.Settings(SettingsSection.Machine, sub = "worktrees"))
        for (r in cases) assertEquals(UrlTarget.Screen(r), parsePath(pathFor(r, null)), r.toString())
        assertEquals(UrlTarget.Session("abc"), parsePath("/s/abc"))
    }
    @Test fun unknownAndLegacyPathsGoHome() {
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/nope/what"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/setup"))              // the wizard owns it; UrlSync never applies it
        assertEquals(UrlTarget.Screen(Route.Settings()), parsePath("/settings"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/settings/keyboard")) // dropped page
    }
    @Test fun providersHaveTheirOwnSlugAndTheOldOneStillLands() {
        assertEquals("/settings/providers", pathFor(Route.Settings(SettingsSection.Providers), null))
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Providers)), parsePath("/settings/agents"))
    }
    @Test fun theRemovedAgentsPagesHaveNoRoute() {
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/agents"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/personal-assistants"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/settings/assistant"))
    }
}
