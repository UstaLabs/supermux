package dev.supermux.android.pairing

import dev.supermux.pairing.InMemoryPairingTokenStore
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.PairingUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PairingHolder] is the ONE Android-shaped thing left in the pairing flow: the retained home
 * that makes a rotation mid-probe survivable. These are its two obligations —
 *  - the state is one instance for the holder's life (so `viewModel()` hands the SAME machine
 *    back to a re-created activity, keeping `Validating` and any open TOFU dialog),
 *  - and clearing the holder releases the throwaway probe client, exactly as the deleted
 *    `PairingViewModel.onCleared` did.
 *
 * `viewModelScope` runs on `Dispatchers.Main.immediate`, which a plain JVM unit test has to
 * install — hence [setMain].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingHolderTest {

    @BeforeTest fun installMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun restoreMain() = Dispatchers.resetMain()

    private fun holder(onClient: (HttpClient) -> Unit = {}) = PairingHolder { scope ->
        PairingState(
            store = InMemoryPairingTokenStore(),
            scope = scope,
            httpFactory = {
                HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) })
                    .also(onClient)
            },
        )
    }

    @Test fun the_state_machine_is_one_instance_for_the_holders_life() {
        val h = holder()
        assertSame(h.pairing, h.pairing)
        assertIs<PairingUiState.Idle>(h.pairing.state.value)
    }

    @Test fun clearing_the_holder_releases_the_probe_client() = runTest {
        val built = CompletableDeferred<HttpClient>()
        val h = holder { built.complete(it) }
        // A real probe (no probeOverride) is what builds the lazy client; the mock engine
        // rejects the token, so the machine lands on Error without ever opening a socket.
        // The probe runs on viewModelScope, off the test scheduler, so wait for it in real time.
        h.pairing.validate("https://host:9898/pair?t=abc123")
        val client = withContext(Dispatchers.Default) { withTimeout(10_000) { built.await() } }
        val settled = withContext(Dispatchers.Default) {
            withTimeout(10_000) { h.pairing.state.first { it !is PairingUiState.Validating } }
        }
        assertIs<PairingUiState.Error>(settled)
        assertTrue(client.isActive)

        h.clearForTest()
        assertFalse(client.isActive)
    }

    @Test fun clearing_a_holder_that_never_probed_is_safe() {
        val h = holder()
        h.clearForTest()
        assertIs<PairingUiState.Idle>(h.pairing.state.value)
    }
}
