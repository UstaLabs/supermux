package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.GitRequirement
import dev.supermux.net.HostRequirements
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `host_requirements` lands in [HostStore.hostRequirements], replacing the previous value. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostRequirementsStateTest {

    private fun store(): HostStore {
        val engine = MockEngine { respond(ByteReadChannel("{}"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun the_frame_sets_and_replaces_the_requirement() {
        val s = store()
        assertNull(s.hostRequirements.value)
        val missing = HostRequirements(GitRequirement(ok = false, install = "browser", hint = "Download Git"))
        s.reduce(ServerFrame.HostRequirementsChanged(missing))
        assertEquals(missing, s.hostRequirements.value)
        s.reduce(ServerFrame.HostRequirementsChanged(HostRequirements(GitRequirement(ok = true, install = "browser"))))
        assertEquals(false, s.hostRequirements.value!!.gitMissing)
    }

    @Test fun browser_is_a_one_click_install_and_manual_is_not() {
        assertEquals(true, GitRequirement(ok = false, install = GitRequirement.INSTALL_BROWSER).installable)
        assertEquals(false, GitRequirement(ok = false, install = GitRequirement.INSTALL_MANUAL).installable)
    }
}
