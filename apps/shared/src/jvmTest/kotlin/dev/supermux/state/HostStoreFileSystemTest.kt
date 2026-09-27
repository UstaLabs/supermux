package dev.supermux.state

import dev.supermux.fs.DirState
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 14: HostStore wires the host [dev.supermux.fs.FileSystemService] into its reducer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreFileSystemTest {
    @Test fun fsFramesReachTheServiceAndASnapshotResubscribes() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond("{}") })
        val store = HostStore(
            "http://h", "t", backgroundScope, testDeps(http = http),
            connectOnInit = false,
            sendFrameOverride = { sent += it },
            apiOverride = BrokerApi("http://h", "t", http),
        )
        store.fileSystem.subscribe("/p")
        assertEquals(ClientFrame.FsSub("/p"), sent.last())
        store.reduce(ServerFrame.FsDir(path = "/p", version = "b:1"))
        assertIs<DirState.Ready>(store.fileSystem.dir("/p").value)
        sent.clear()
        store.reduce(ServerFrame.Snapshot())  // every (re)connect starts with a snapshot
        assertTrue(sent.contains(ClientFrame.FsSub("/p", since = "b:1")))
    }
}
