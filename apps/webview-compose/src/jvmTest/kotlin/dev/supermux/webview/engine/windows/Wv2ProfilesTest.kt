package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebProfile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wv2ProfilesTest {
    @Test fun profilesHaveWebView2NamesOfTheirOwn() {
        assertEquals("", Wv2Profiles.profileName(WebProfile.Default), "WebView2's own default profile")
        val named = Wv2Profiles.profileName(WebProfile.Named("Work_Account-1"))!!
        assertTrue(named.matches(Regex("wvc-n-[a-z0-9_-]{1,32}-[0-9a-f]{8}")), named)
        assertTrue(named.length <= 64)
        assertTrue(Wv2Profiles.profileName(WebProfile.Named("A".repeat(64)))!!.length <= 64, "the longest id too")
        assertNotEquals(named, Wv2Profiles.profileName(WebProfile.Named("work_account-1")), "ids differing in case stay apart")
        assertNull(Wv2Profiles.profileName(WebProfile.Ephemeral), "an ephemeral engine takes a slot")
    }

    @Test fun anEphemeralSlotIsNeverTwoEnginesAtOnceInAnyProcess() {
        val folder = Files.createTempDirectory("udf").toFile()
        val first = Wv2Profiles.Slots(folder)
        val second = Wv2Profiles.Slots(folder)   // as another process's: a lock of its own on the same files
        val a = first.claim()!!
        val b = first.claim()!!
        assertNotEquals(a, b)
        val c = second.claim()!!
        assertTrue(c != a && c != b, "another holder never gets a held slot")
        first.release(a)
        assertEquals(a, second.claim(), "a released slot is the lowest free one again")
    }
}
