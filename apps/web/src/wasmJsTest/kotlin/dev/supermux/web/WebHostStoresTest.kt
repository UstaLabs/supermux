package dev.supermux.web

import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.PairedHost
import kotlinx.browser.localStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebHostStoresTest {
    /**
     * A browser that ran the plan-2 build has the origin record on disk with the sentinel token
     * `"cookie"` and no `ambientAuth` flag. Left alone, `FleetStore.sync` would send that string as
     * a Bearer on every dial and spend the broker's brute-force budget, so the first
     * [WebHostStores.ensureOriginHost] of the session must repair it in place — in place, because
     * remove+add would empty the registry and fire the sign-out hook.
     *
     * Deliberately the ONLY test in this class: `WebHostStores.store` is a lazy on an `object`, so
     * the first call in the Karma realm is the one that reads localStorage; a second test would
     * seed storage that the already-constructed store never looks at.
     */
    @Test fun ensureOriginHostRepairsTheSentinelRecord() {
        localStorage.clear()
        LocalStorageHostPersistence().saveAll(
            listOf(
                PairedHost(
                    recordId = WebHostStores.RECORD_ID,
                    displayName = "This host",
                    token = "cookie",
                    directUrl = "http://127.0.0.1:9898",
                    ambientAuth = false,
                ),
            ),
        )

        // Prove the seed is what the store will actually load. `WebHostStores.store` is a lazy on an
        // `object`: if some earlier test in this Karma realm had already forced it, the assertions
        // below would pass against a store that never saw the sentinel and prove nothing.
        assertEquals("cookie", WebHostStores.store.list().single().token)

        WebHostStores.ensureOriginHost()

        val host = WebHostStores.store.list().single()
        assertEquals(WebHostStores.RECORD_ID, host.recordId)
        assertEquals("", host.token)
        assertTrue(host.ambientAuth, "the repaired record must dial on the cookie")
        // The sentinel must be gone from the persisted token map too, not just from memory.
        assertFalse(localStorage.getItem("supermux:hostTokens").orEmpty().contains("cookie"))
    }
}
