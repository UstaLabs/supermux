package dev.supermux.host

/** Platform storage. Implementations put metadata in normal storage and each
 *  host's token in the secure store (spec §3.2) — the store logic is agnostic. */
interface HostPersistence {
    fun loadAll(): List<PairedHost>
    fun saveAll(hosts: List<PairedHost>)
}
