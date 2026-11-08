package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebProfile
import platform.WebKit.WKWebsiteDataStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Where each profile's cookies and storage live. Named profiles never share a store with another profile: from iOS 17
 * each has a persistent store of its own; before, WebKit has no named stores, and each gets a non-persistent store of its
 * own instead of the default one (fail closed: separate, forgotten when the app quits).
 */
class ProfileStoresTest {
    @AfterTest fun freeWhatTheTestLeft() = collectGarbage()

    @Test fun beforeIos17ANamedProfileHasAStoreOfItsOwnForTheAppsLifeSharedByItsEngines() {
        val alice = dataStoreFor(WebProfile.Named("stores-alice"), namedStores = false)
        assertSame(alice, dataStoreFor(WebProfile.Named("stores-alice"), namedStores = false), "the same profile's next engine")
        assertNotSame(alice, dataStoreFor(WebProfile.Named("stores-bob"), namedStores = false), "another profile")
        assertNotSame(WKWebsiteDataStore.defaultDataStore(), alice, "never the default store")
        assertFalse(alice.persistent, "forgotten when the app quits")
    }

    @Test fun fromIos17ANamedProfileHasAPersistentStoreOfItsOwn() {
        if (!osAtLeast(17)) return
        val alice = dataStoreFor(WebProfile.Named("stores-alice"), namedStores = true)
        assertTrue(alice.persistent)
        assertEquals(alice.identifier?.UUIDString, profileUuid("stores-alice").UUIDString)
    }

    @Test fun ephemeralEnginesEachHaveTheirOwnStore() {
        assertNotSame(dataStoreFor(WebProfile.Ephemeral, osAtLeast(17)), dataStoreFor(WebProfile.Ephemeral, osAtLeast(17)))
    }

    /** What the app can read: whether named profiles outlive the app's run, here. */
    @Test fun theFactorySaysWhetherNamedProfilesPersist() {
        assertEquals(osAtLeast(17), IosWebEngineFactory().namedProfilesPersist)
    }
}
