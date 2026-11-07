package dev.supermux.state

import dev.supermux.proto.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The broker has always sent its home folder on the snapshot (src/channels/web/index.ts); no client read it, so
 * the app guessed one home from the first session and applied it to every host.
 */
class HostReducerHomeDirTest {
    @Test fun aSnapshotCarriesTheHostsOwnHome() {
        val out = reduceHostFrame(HostState(), ServerFrame.Snapshot(homeDir = "C:\\Users\\me"))
        assertEquals("C:\\Users\\me", out.homeDir)
    }

    @Test fun anOlderBrokerWithoutOneLeavesItUnknown() {
        assertNull(reduceHostFrame(HostState(), ServerFrame.Snapshot()).homeDir)
    }
}
