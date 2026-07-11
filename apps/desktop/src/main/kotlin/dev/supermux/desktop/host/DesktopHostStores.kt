package dev.supermux.desktop.host

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import java.nio.file.Path
import java.util.UUID

/**
 * Builds the multi-host [PairedHostStore] (spec §3.2) over [DesktopHostPersistence] plus the
 * one-time migration from the legacy single-host `(baseUrl, token)` in [DesktopTokenStore].
 *
 * The desktop mirror of `apps/android/.../host/HostStores.kt`: recordIds come from a real UUID RNG,
 * and [migrateFromLegacyIfNeeded] is idempotent — safe to call at every launch and right after
 * onboarding persists the legacy store, so existing paired desktop users land as `PairedHost[0]`
 * with ZERO re-pairing.
 */
object DesktopHostStores {

    /** The shared store over [DesktopHostPersistence] under [dir] (default the platform config dir);
     *  recordId = random UUID. Unlike Android's process-wide singleton, the desktop entry point
     *  (Main) constructs this once and remembers it, so this is a plain factory. */
    fun store(dir: Path = defaultDir()): PairedHostStore =
        PairedHostStore(
            DesktopHostPersistence(dir.resolve(DesktopHostPersistence.META_FILE), dir.resolve(DesktopHostPersistence.TOKEN_FILE)),
        ) { UUID.randomUUID().toString() }

    /**
     * One-time single-host → `PairedHost[0]` migration. Idempotent and safe to call every launch:
     * no-op once [store] holds any host (and [PairedHostStore.migrateFromSingleHost] guards emptiness
     * internally). Reads the [legacy] `(token, baseUrl)`; existing paired users land as `PairedHost[0]`
     * with ZERO re-pairing. Returns the effective first host (or null when there was nothing to seed).
     */
    fun migrateFromLegacyIfNeeded(store: PairedHostStore, legacy: DesktopTokenStore = DesktopTokenStore()): PairedHost? {
        if (store.list().isNotEmpty()) return store.list().firstOrNull()
        val token = legacy.load()
        val baseUrl = legacy.loadBaseUrl()
        if (!token.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
            store.migrateFromSingleHost(token, baseUrl)
            return store.list().firstOrNull()
        }
        return null
    }

    /** `~/.config/supermux-desktop` (or `%APPDATA%\supermux-desktop`) — the same dir
     *  [DesktopTokenStore] uses, so the fleet files sit alongside the legacy `auth.json`. */
    fun defaultDir(): Path = DesktopTokenStore.defaultPath().parent
}
