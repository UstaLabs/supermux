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
 * page, whose credential is the HttpOnly session cookie. Its record therefore has a blank token
 * (`bearer("")` sends no header) and a fixed [RECORD_ID], so a reload finds the same record rather
 * than minting a new one and orphaning the snapshot cache keyed by it.
 */
object WebHostStores {
    const val RECORD_ID = "web-origin"

    /**
     * The origin host's stored token — a SENTINEL, not a credential.
     *
     * The browser's real credential is the HttpOnly `cmux_token` cookie, and `bearer()` is written
     * to send no header when the token is blank, which is the honest shape. But `FleetStore.sync`
     * refuses to dial a host whose token is blank (FleetStore.kt:377) — that check is how every
     * other platform says "this record is not configured yet" — so a blank-token record is never
     * connected and the session list stays empty forever. Until `:shared` learns about the
     * cookie-credential host (see the plan's Results), the record carries this sentinel: the
     * broker resolves `cookieToken(req) || bearerToken(req)`, so the COOKIE wins on every HTTP
     * request and the header is ignored, and a browser cannot set headers on a WS upgrade at all.
     */
    private const val COOKIE_TOKEN = "cookie"

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
     * Make sure the origin host exists (first paired launch). An existing record is left alone —
     * its `hostId`/`platform`/`version` are backfilled from `GET /host` by `FleetStore`'s probe,
     * and its display name may be a rename the user made.
     */
    fun ensureOriginHost(displayName: String, hostId: String? = null, platform: String? = null, version: String? = null) {
        if (store.list().any { it.recordId == RECORD_ID }) return
        store.add(
            displayName = displayName,
            token = COOKIE_TOKEN,
            relayUrl = null,
            directUrl = window.location.origin,
            hostId = hostId,
            platform = platform,
            version = version,
        )
    }
}
