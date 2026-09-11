package dev.supermux.web

import dev.supermux.host.HostSnapshotStore
import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.LocalStorageSnapshotPersistence
import dev.supermux.host.PairedHostStore
import dev.supermux.web.auth.CookieSession
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The browser's host registry — the web twin of `IosHostStores`.
 *
 * There is exactly ONE host here and there can never be a second: the origin that served this
 * page, whose credential is the HttpOnly session cookie. The record therefore carries NO token at
 * all — it is flagged `ambientAuth`, which is how `FleetStore.sync` knows the transport already
 * carries the credential and dials a blank-token host. The record id is fixed, so a reload finds
 * the same record rather than minting a new one and orphaning the snapshot cache keyed by it.
 */
object WebHostStores {
    const val RECORD_ID = "web-origin"

    /**
     * What to run when the registry is emptied — i.e. the user unpaired the origin host from the
     * Devices screen. Forgetting the record is only half of signing a browser out; the cookie is
     * the actual credential and only the broker can expire it, so this ends in `POST /logout` +
     * reload. Set by [init] before the first store read; a no-op until then.
     */
    private var onForgotten: () -> Unit = {}

    val store: PairedHostStore by lazy {
        PairedHostStore(LocalStorageHostPersistence(onEmptied = { onForgotten() })) { RECORD_ID }
    }

    val snapshots: HostSnapshotStore by lazy { HostSnapshotStore(LocalStorageSnapshotPersistence()) }

    /** Wire sign-out to [session]. Called from `main()` before anything touches [store]. */
    fun init(session: CookieSession, scope: CoroutineScope) {
        onForgotten = { scope.launch { session.logout() } }
    }

    /**
     * Make sure the origin host exists (first paired launch). An existing record is left alone: its
     * `hostId`/`platform`/`version` — and its NAME — are backfilled from `GET /host` by
     * `FleetStore`'s probe.
     *
     * The seed name is deliberately the legacy-shaped `"This host"`, which
     * `PairedHostStore.resolvedIdentityName` treats as "no name yet" and replaces with the broker's
     * real one on that first backfill. Seeding `window.location.host` instead would look like a
     * name the USER chose, and the browser would show `127.0.0.1:9898` forever.
     */
    fun ensureOriginHost(displayName: String = "This host", hostId: String? = null, platform: String? = null, version: String? = null) {
        if (store.list().any { it.recordId == RECORD_ID }) return
        store.add(
            displayName = displayName,
            token = "",
            relayUrl = null,
            directUrl = window.location.origin,
            hostId = hostId,
            platform = platform,
            version = version,
            // The HttpOnly `cmux_token` cookie IS the credential: `bearer()` sends no header for a
            // blank token, and a browser cannot set headers on a WS upgrade anyway. Never a
            // sentinel token — that would spend the broker's brute-force budget once it expires.
            ambientAuth = true,
        )
    }
}
