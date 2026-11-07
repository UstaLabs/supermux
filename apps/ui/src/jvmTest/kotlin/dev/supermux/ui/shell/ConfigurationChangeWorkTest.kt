// A configuration change must not drop work in flight, nor a form half filled in (card #89, phase 2
// batch B): Settings' forms and typed secrets, the page Settings has open, an Add host claim, the
// Files pane's create / rename / delete, the Finish flow, a display's password and the onboarding
// scan.
//
// The same two events as ConfigurationChangeTest, on the real shell where the shell reaches the
// spot: [Recreation] for an Android recreation (rotate, fold, split-screen, dark mode, font scale),
// and a Compact→Expanded flip inside one composition for the layout swap (a phone turned to
// landscape, a Fold opened; all iOS gets). Work in flight is held at the mock broker by a [Gate]:
// the event happens mid-request, then the request is released and the outcome must land.
//
// Three spots run one level below the shell, recreated by a dispose/recompose and swapped by a width
// flip: the Finish flow (only Android's session-only chat draws it) and a display's VNC password (a
// display needs a live RFB socket, so its clients here never connect), both inside the fleet's
// retained state provided by hand and rebuilt in a Row instead of a Column for the swap, and the
// onboarding intro (shown before there is a shell at all).
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishResult
import dev.supermux.net.FsEntry
import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.VncClient
import dev.supermux.net.VncStatus
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.RetainedUiState
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FinishBindings
import dev.supermux.ui.chat.FinishHeaderButton
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.display.DisplayActions
import dev.supermux.ui.display.DisplayPanel
import dev.supermux.ui.display.DisplaysScreen
import dev.supermux.ui.intro.OnboardingFlow
import dev.supermux.ui.intro.testPairing
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.retain.LocalRetainedUiState
import dev.supermux.ui.session.LocalContextMenuAvailable
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.toDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Holds the first request it sees until [release] completes; later ones pass straight through. */
private class Gate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val first = AtomicBoolean(true)

    suspend fun hold() {
        if (first.getAndSet(false)) {
            started.complete(Unit)
            release.await()
        }
    }
}

/**
 * A broker for the mock engine: answers each "METHOD /path" with [answers] ("{}" when it has
 * none), holds a request at its [gates], and records every request it saw.
 */
private class Broker(
    private val gates: Map<String, Gate> = emptyMap(),
    private val answers: (key: String) -> String? = { null },
) {
    constructor(answers: Map<String, String>, gates: Map<String, Gate> = emptyMap()) : this(gates, { answers[it] })

    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val handler: MockRequestHandler = { request ->
        val key = "${request.method.value} ${request.url.encodedPath}"
        requests += key
        gates[key]?.hold()
        respond(
            answers(key) ?: "{}",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    fun count(key: String): Int = synchronized(requests) { requests.count { it == key } }
}

/**
 * Android's phone around the shell: no platform context menu (a row's actions are in its ⋮ or its
 * long press), and a hardware keyboard only when [keyboard], which picks the Files pane's in-place
 * name field over its dialogs.
 */
private fun phone(keyboard: Boolean = false): @Composable (@Composable () -> Unit) -> Unit = { content ->
    CompositionLocalProvider(
        LocalContextMenuAvailable provides false,
        LocalHardwareKeyboard provides keyboard,
        content = content,
    )
}

/** What a field shows for a secret typed into it: a password field shows a dot per character. */
private fun masked(secret: String): String = "•".repeat(secret.length)

/** The desktop host wizard's claim QR, as the intro reads it. */
private const val CLAIM_QR =
    """{"v":1,"action":"pair","hostId":"amsfen6cjgpd7pjqw6nimlahvy","name":"studio",""" +
        """"relayUrl":"https://h-amsfen6cjgpd7pjqw6nimlahvy.relay.supermux.dev","directUrl":"http://127.0.0.1:9898",""" +
        """"claimSecret":"s3cret"}"""

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ConfigurationChangeWorkTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The phone's shell over [app] in a [Recreation]: MainActivity's saveable [ShellUiState], started
     * by [initial]. The fleet and the platform are built here, outside the content, as the view model
     * holds them through a recreation. [settings] draws the fleet's real Settings pages.
     */
    private fun ComposeUiTest.phoneShell(
        app: HostStore,
        fleet: FleetStore = testFleet(app),
        platform: FakePlatform = FakePlatform(),
        keyboard: Boolean = false,
        settings: Boolean = false,
        initial: ShellUiState.() -> Unit = {},
    ): Recreation = Recreation(this).also {
        it.setShell(fleet, platform, UiPrefs(FakeSettingsStore()), wrap = phone(keyboard), settings = settings, initial = initial)
    }

    /** The same phone's shell at a width the test flips to Expanded: the phone turned, the Fold opened. */
    private fun ComposeUiTest.flippablePhone(
        app: HostStore,
        fleet: FleetStore = testFleet(app),
        platform: FakePlatform = FakePlatform(),
        keyboard: Boolean = false,
        settings: Boolean = false,
        ui: ShellUiState = ShellUiState(),
        initial: ShellUiState.() -> Unit = {},
    ): MutableState<WindowWidthClass> =
        swappableShell(fleet, ui.apply(initial), platform = platform, wrap = phone(keyboard), settings = settings)

    private fun ComposeUiTest.unfold(width: MutableState<WindowWidthClass>) {
        width.value = WindowWidthClass.Expanded
        waitForIdle()
    }

    /** The mock broker answers on its own thread, which idling does not track: wait for what it brings. */
    private fun ComposeUiTest.await(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitGone(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun ComposeUiTest.awaitHeld(gate: Gate) = waitUntil(timeoutMillis = 10_000) { gate.started.isCompleted }

    /** The fleet's retained state, as `SupermuxApp` provides it; built outside the content. */
    private fun retainedUi() = RetainedUiState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun prefs() = UiPrefs(FakeSettingsStore())

    // ── Settings: the page that is open ──────────────────────────────────────────────────────────

    private fun ComposeUiTest.openFromTheIndex(row: String, page: String) {
        await(row)
        onNodeWithTag(row).performScrollTo().performClick()
        await(page)
    }

    private fun devicesHost() = testHostStore(Broker(mapOf("GET /devices" to "[]")).handler)

    @Test fun the_settings_page_open_on_a_phone_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(devicesHost(), settings = true) { openSettings() }
        openFromTheIndex("settings_row_devices", "devices_settings_screen")

        activity.recreate()

        // Back on Devices, not on the index it used to land on.
        await("devices_settings_screen")
        onNodeWithTag("settings_index").assertDoesNotExist()
    }

    @Test fun the_settings_page_open_on_a_phone_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val ui = ShellUiState()
        val width = flippablePhone(devicesHost(), settings = true, ui = ui) { openSettings() }
        openFromTheIndex("settings_row_devices", "devices_settings_screen")

        unfold(width)

        // The wide hub shows the page the phone had open (it showed the rail's own section), and
        // its rail says so.
        await("settings_hub_rail")
        await("devices_settings_screen")
        assertEquals(SettingsSection.Devices, ui.settingsSection)
    }

    private fun withAppearance() = FakePlatform(caps = NO_CAPS.copy(appearanceControls = true))

    @Test fun an_extra_settings_page_open_on_a_phone_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testHostStore(), platform = withAppearance(), settings = true) { openSettings() }
        openFromTheIndex("settings_row_appearance", "appearance_settings_screen")

        activity.recreate()

        await("appearance_settings_screen")
        onNodeWithTag("settings_index").assertDoesNotExist()
    }

    @Test fun an_extra_settings_page_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testHostStore(), platform = withAppearance(), settings = true) { openSettings() }
        openFromTheIndex("settings_row_appearance", "appearance_settings_screen")

        unfold(width)

        await("settings_hub_rail")
        await("appearance_settings_screen")
    }

    // ── Settings › Providers: a pasted API key, the OpenCode code step, an agent's login code ────

    private val claudeToSignIn = """[{"kind":"claude","installed":true,"authed":false}]"""
    private val openCodeToSignIn = """[{"kind":"opencode","installed":true,"authed":false}]"""

    private fun ComposeUiTest.openProviders() {
        await("settings_row_providers")
        onNodeWithTag("settings_row_providers").performScrollTo().performClick()
    }

    private fun ComposeUiTest.pasteAnApiKey(key: String) {
        openProviders()
        await("agent_secret_claude")
        onNodeWithTag("agent_secret_claude").performTextInput(key)
        waitForIdle()
    }

    @Test fun a_pasted_api_key_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(testHostStore(Broker(mapOf("GET /agents/status" to claudeToSignIn)).handler), settings = true) {
            openSettings()
        }
        pasteAnApiKey("sk-ant-api-key")

        activity.recreate()

        await("agent_secret_claude")
        assertEquals(masked("sk-ant-api-key"), onNodeWithTag("agent_secret_claude").editableText())
    }

    @Test fun a_pasted_api_key_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(testHostStore(Broker(mapOf("GET /agents/status" to claudeToSignIn)).handler), settings = true) {
            openSettings()
        }
        pasteAnApiKey("sk-ant-api-key")

        unfold(width)

        await("agent_secret_claude")
        assertEquals(masked("sk-ant-api-key"), onNodeWithTag("agent_secret_claude").editableText())
    }

    private fun openCodeHost(providers: String) = testHostStore(
        Broker(
            mapOf(
                "GET /agents/status" to openCodeToSignIn,
                "GET /opencode/providers" to providers,
                "POST /opencode/auth/oauth/start" to """{"url":"https://openrouter.ai/auth?state=1","method":"code"}""",
            ),
        ).handler,
    )

    private fun ComposeUiTest.pasteAnOpenCodeKey(key: String) {
        openProviders()
        await("opencode_zen_key_field")
        onNodeWithTag("opencode_zen_key_field").performTextInput(key)
        waitForIdle()
    }

    @Test fun a_pasted_opencode_key_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(openCodeHost("[]"), settings = true) { openSettings() }
        pasteAnOpenCodeKey("zen-key")

        activity.recreate()

        await("opencode_zen_key_field")
        assertEquals(masked("zen-key"), onNodeWithTag("opencode_zen_key_field").editableText())
    }

    @Test fun a_pasted_opencode_key_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(openCodeHost("[]"), settings = true) { openSettings() }
        pasteAnOpenCodeKey("zen-key")

        unfold(width)

        await("opencode_zen_key_field")
        assertEquals(masked("zen-key"), onNodeWithTag("opencode_zen_key_field").editableText())
    }

    private val browserProvider = """[{"id":"openrouter","configured":false,"methods":[{"type":"oauth","label":"Browser","index":0}]}]"""

    /** "Login via browser": the browser opens on the provider's page, and the code it gives is pasted back. */
    private fun ComposeUiTest.pasteTheBrowserCode(code: String) {
        openProviders()
        awaitText("Login via browser")
        onNodeWithText("Login via browser").performClick()
        await("opencode_oauth_code_openrouter")
        onNodeWithTag("opencode_oauth_code_openrouter").performTextInput(code)
        waitForIdle()
    }

    @Test fun the_opencode_paste_code_step_survives_a_recreation() = runComposeUiTest {
        val platform = FakePlatform()
        val activity = phoneShell(openCodeHost(browserProvider), platform = platform, settings = true) { openSettings() }
        pasteTheBrowserCode("CODE-FROM-BROWSER")

        activity.recreate()

        // Still on the step, with the code: it only finishes the sign-in it was started for.
        await("opencode_oauth_code_openrouter")
        assertEquals("CODE-FROM-BROWSER", onNodeWithTag("opencode_oauth_code_openrouter").editableText())
        // And the browser was sent there once, not again by the rebuilt screen.
        assertEquals(listOf("https://openrouter.ai/auth?state=1"), platform.openedUrls)
    }

    @Test fun the_opencode_paste_code_step_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val platform = FakePlatform()
        val width = flippablePhone(openCodeHost(browserProvider), platform = platform, settings = true) { openSettings() }
        pasteTheBrowserCode("CODE-FROM-BROWSER")

        unfold(width)

        await("opencode_oauth_code_openrouter")
        assertEquals("CODE-FROM-BROWSER", onNodeWithTag("opencode_oauth_code_openrouter").editableText())
        assertEquals(listOf("https://openrouter.ai/auth?state=1"), platform.openedUrls)
    }

    /** Claude, signing in by link: once started, the host waits for a code pasted back. */
    private fun signingInHost(): HostStore {
        val started = AtomicBoolean(false)
        val awaiting = """{"kind":"claude","phase":"awaiting_user","url":"https://claude.ai/oauth","needsCode":true}"""
        return testHostStore(
            Broker { key ->
                when (key) {
                    "GET /agents/status" -> claudeToSignIn
                    "POST /agents/claude/login" -> awaiting.also { started.set(true) }
                    "GET /agents/claude/login" -> if (started.get()) awaiting else "{}"
                    else -> null
                }
            }.handler,
        )
    }

    private fun ComposeUiTest.pasteALoginCode(code: String) {
        openProviders()
        await("agent_login_start_claude")
        onNodeWithTag("agent_login_start_claude").performClick()
        await("agent_login_code_field")
        onNodeWithTag("agent_login_code_field").performTextInput(code)
        waitForIdle()
    }

    @Test fun a_login_code_being_pasted_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(signingInHost(), settings = true) { openSettings() }
        pasteALoginCode("LOGIN-CODE")

        activity.recreate()

        // The login resumes from the host, and the code pasted for it is still there.
        await("agent_login_code_field")
        assertEquals("LOGIN-CODE", onNodeWithTag("agent_login_code_field").editableText())
    }

    @Test fun a_login_code_being_pasted_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(signingInHost(), settings = true) { openSettings() }
        pasteALoginCode("LOGIN-CODE")

        unfold(width)

        await("agent_login_code_field")
        assertEquals("LOGIN-CODE", onNodeWithTag("agent_login_code_field").editableText())
    }

    // ── Settings › Git hosting: a token being pasted into the add form ──────────────────────────

    private fun gitHost() = testHostStore(
        Broker(mapOf("GET /forge/connections" to """{"connections":[],"cli":{"github":{"available":false},"gitlab":{"available":false}}}""")).handler,
    )

    private fun ComposeUiTest.pasteAGitToken(token: String) {
        await("git_hosting_manual_github")
        onNodeWithTag("git_hosting_manual_github").performClick()
        await("git_hosting_token")
        onNodeWithTag("git_hosting_token").performTextInput(token)
        waitForIdle()
    }

    @Test fun the_git_account_form_and_its_token_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(gitHost(), settings = true) { openSettings(SettingsSection.GitHosting) }
        pasteAGitToken("ghp_token")

        activity.recreate()

        await("git_hosting_token")
        assertEquals(masked("ghp_token"), onNodeWithTag("git_hosting_token").editableText())
    }

    @Test fun the_git_account_form_and_its_token_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(gitHost(), settings = true) { openSettings(SettingsSection.GitHosting) }
        pasteAGitToken("ghp_token")

        unfold(width)

        await("git_hosting_token")
        assertEquals(masked("ghp_token"), onNodeWithTag("git_hosting_token").editableText())
    }

    // ── Settings › Accounts: the add sheet, a token pasted into it, a sign-in in flight ──────────

    private fun accountsHost(gate: Gate? = null): Pair<HostStore, Broker> {
        val broker = Broker(
            mapOf(
                "GET /accounts" to """{"accounts":[{"id":"claude:system","agent":"claude","method":"system","label":"This machine","system":true}]}""",
                "GET /settings/accounts" to """{"autoSwitch":false}""",
                "POST /accounts/login" to """{"loginId":"L1","agent":"claude","phase":"awaiting_user","url":"https://claude.ai/oauth","needsCode":true}""",
            ),
            gates = gate?.let { mapOf("POST /accounts/login" to it) }.orEmpty(),
        )
        return testHostStore(broker.handler) to broker
    }

    private fun ComposeUiTest.pasteAnAccountToken(token: String) {
        await("account-add:claude")
        onNodeWithTag("account-add:claude").performClick()
        await("account-add-option:token")
        onNodeWithTag("account-add-option:token").performClick()
        await("account-token-field")
        onNodeWithTag("account-token-field").performTextInput(token)
        waitForIdle()
    }

    @Test fun the_add_account_sheet_and_its_token_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(accountsHost().first, settings = true) { openSettings(SettingsSection.Accounts) }
        pasteAnAccountToken("sk-ant-oat01-token")

        activity.recreate()

        await("account-token-field")
        assertEquals(masked("sk-ant-oat01-token"), onNodeWithTag("account-token-field").editableText())
    }

    @Test fun the_add_account_sheet_and_its_token_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(accountsHost().first, settings = true) { openSettings(SettingsSection.Accounts) }
        pasteAnAccountToken("sk-ant-oat01-token")

        unfold(width)

        await("account-token-field")
        assertEquals(masked("sk-ant-oat01-token"), onNodeWithTag("account-token-field").editableText())
    }

    private fun ComposeUiTest.startAnAccountSignIn(gate: Gate) {
        await("account-add:claude")
        onNodeWithTag("account-add:claude").performClick()
        await("account-add-option:login")
        onNodeWithTag("account-add-option:login").performClick()
        awaitHeld(gate)
    }

    @Test fun an_account_sign_in_in_flight_lands_through_a_recreation() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = accountsHost(gate)
        val activity = phoneShell(app, settings = true) { openSettings(SettingsSection.Accounts) }
        startAnAccountSignIn(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The sign-in the host started is the one on screen: its link is offered, once.
        await("account-login-open")
        assertEquals(1, broker.count("POST /accounts/login"))
    }

    @Test fun an_account_sign_in_in_flight_lands_through_the_compact_to_wide_swap() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = accountsHost(gate)
        val width = flippablePhone(app, settings = true) { openSettings(SettingsSection.Accounts) }
        startAnAccountSignIn(gate)

        unfold(width)
        gate.release.complete(Unit)

        await("account-login-open")
        assertEquals(1, broker.count("POST /accounts/login"))
    }

    private fun ComposeUiTest.pasteASignInCode(code: String) {
        await("account-add:claude")
        onNodeWithTag("account-add:claude").performClick()
        await("account-add-option:login")
        onNodeWithTag("account-add-option:login").performClick()
        await("account-login-code")
        onNodeWithTag("account-login-code").performTextInput(code)
        waitForIdle()
    }

    @Test fun a_code_pasted_into_an_account_sign_in_survives_a_recreation() = runComposeUiTest {
        val (app, broker) = accountsHost()
        val activity = phoneShell(app, settings = true) { openSettings(SettingsSection.Accounts) }
        pasteASignInCode("CODE#1")

        activity.recreate()

        await("account-login-code")
        assertEquals("CODE#1", onNodeWithTag("account-login-code").editableText())
        assertEquals(1, broker.count("POST /accounts/login"))
    }

    @Test fun a_code_pasted_into_an_account_sign_in_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val (app, broker) = accountsHost()
        val width = flippablePhone(app, settings = true) { openSettings(SettingsSection.Accounts) }
        pasteASignInCode("CODE#1")

        unfold(width)

        await("account-login-code")
        assertEquals("CODE#1", onNodeWithTag("account-login-code").editableText())
        assertEquals(1, broker.count("POST /accounts/login"))
    }

    // ── Settings › Proxies: the expose form, the remove and make-public confirms ────────────────

    private fun proxiesHost() = oneChatWorkspace(
        Broker(mapOf("GET /proxies" to """[{"domain":"app.test","sessionName":"worker-1","port":3000,"isPublic":false}]""")).handler,
    )

    private fun ComposeUiTest.fillTheExposeForm() {
        await("proxies_expose_button")
        onNodeWithTag("proxies_expose_button").performClick()
        await("proxies_create_port")
        onNodeWithTag("proxies_create_port").performTextInput("8080")
        onNodeWithTag("proxies_create_domain").performTextInput("demo")
        waitForIdle()
    }

    private fun ComposeUiTest.assertTheExposeForm() {
        await("proxies_create_port")
        assertEquals("8080", onNodeWithTag("proxies_create_port").editableText())
        assertEquals("demo", onNodeWithTag("proxies_create_domain").editableText())
    }

    @Test fun the_expose_port_form_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        fillTheExposeForm()

        activity.recreate()

        assertTheExposeForm()
    }

    @Test fun the_expose_port_form_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        fillTheExposeForm()

        unfold(width)

        assertTheExposeForm()
    }

    private fun ComposeUiTest.askToRemoveTheProxy() {
        await("proxy_remove_app.test")
        onNodeWithTag("proxy_remove_app.test").performClick()
        await("proxies_remove_dialog")
    }

    @Test fun the_remove_proxy_confirm_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        askToRemoveTheProxy()

        activity.recreate()

        await("proxies_remove_dialog")
        onNodeWithText("Remove proxy for \"app.test\"?").assertExists()
    }

    @Test fun the_remove_proxy_confirm_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        askToRemoveTheProxy()

        unfold(width)

        await("proxies_remove_dialog")
        onNodeWithText("Remove proxy for \"app.test\"?").assertExists()
    }

    private fun ComposeUiTest.askToMakeTheProxyPublic() {
        await("proxy_public_switch_app.test")
        onNodeWithTag("proxy_public_switch_app.test").performClick()
        await("proxies_public_dialog")
    }

    @Test fun the_make_public_confirm_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        askToMakeTheProxyPublic()

        activity.recreate()

        await("proxies_public_dialog")
    }

    @Test fun the_make_public_confirm_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(proxiesHost(), settings = true) { openSettings(SettingsSection.Proxies) }
        askToMakeTheProxyPublic()

        unfold(width)

        await("proxies_public_dialog")
    }

    // ── Settings › Editor: the "Add language server" form ────────────────────────────────────────

    private fun lspHost() = testHostStore(Broker(mapOf("GET /settings/editor" to """{"lsp":{"servers":[]}}""")).handler)

    private fun ComposeUiTest.describeALanguageServer() {
        await("lsp_add_toggle")
        onNodeWithTag("lsp_add_toggle").performScrollTo().performClick()
        await("lsp_add_label")
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        waitForIdle()
    }

    private fun ComposeUiTest.assertTheLanguageServer() {
        await("lsp_add_label")
        assertEquals("Zig", onNodeWithTag("lsp_add_label").editableText())
        assertEquals("zls", onNodeWithTag("lsp_add_command").editableText())
    }

    @Test fun the_add_language_server_form_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(lspHost(), settings = true) { openLspSettings() }
        describeALanguageServer()

        activity.recreate()

        assertTheLanguageServer()
    }

    @Test fun the_add_language_server_form_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(lspHost(), settings = true) { openLspSettings() }
        describeALanguageServer()

        unfold(width)

        assertTheLanguageServer()
    }

    // ── Settings › Voice: the open glossary and the term being typed ─────────────────────────────

    private fun voiceHost() = testHostStore(Broker(mapOf("GET /config/voice-glossary" to """{"glossary":["supermux"]}""")).handler)

    private fun ComposeUiTest.typeAGlossaryTerm(term: String) {
        await("voice_glossary_link")
        onNodeWithTag("voice_glossary_link").performScrollTo().performClick()
        await("voice_glossary_input")
        awaitText("supermux")
        onNodeWithTag("voice_glossary_input").performTextInput(term)
        waitForIdle()
    }

    @Test fun the_voice_glossary_and_its_new_term_survive_a_recreation() = runComposeUiTest {
        val activity = phoneShell(voiceHost(), settings = true) { openSettings(SettingsSection.Voice) }
        typeAGlossaryTerm("Kotlin")

        activity.recreate()

        await("voice_glossary_input")
        assertEquals("Kotlin", onNodeWithTag("voice_glossary_input").editableText())
    }

    @Test fun the_voice_glossary_and_its_new_term_survive_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(voiceHost(), settings = true) { openSettings(SettingsSection.Voice) }
        typeAGlossaryTerm("Kotlin")

        unfold(width)

        // On a wide hub the glossary opens in place instead of as a page of its own.
        await("voice_glossary_input")
        assertEquals("Kotlin", onNodeWithTag("voice_glossary_input").editableText())
    }

    // ── Settings › Curator: a schedule changed and not yet saved ─────────────────────────────────

    private fun curatorHost() = testHostStore(
        Broker(mapOf("GET /settings/curator" to """{"config":{"enabled":false,"hour":1,"minute":0,"agent":"claude"}}""")).handler,
    )

    private fun ComposeUiTest.switchTheCuratorOn() {
        await("assistant_curator_enabled")
        onNodeWithTag("assistant_curator_enabled").performClick()
        waitForIdle()
        onNodeWithTag("assistant_curator_enabled").assertIsOn()
    }

    @Test fun an_unsaved_curator_change_survives_a_recreation() = runComposeUiTest {
        val activity = phoneShell(curatorHost(), settings = true) { openSettings(SettingsSection.Curator) }
        switchTheCuratorOn()

        activity.recreate()

        // Not reloaded from the host (where it is still off) over the user's change.
        await("assistant_curator_enabled")
        onNodeWithTag("assistant_curator_enabled").assertIsOn()
    }

    @Test fun an_unsaved_curator_change_survives_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(curatorHost(), settings = true) { openSettings(SettingsSection.Curator) }
        switchTheCuratorOn()

        unfold(width)

        await("assistant_curator_enabled")
        onNodeWithTag("assistant_curator_enabled").assertIsOn()
    }

    // ── Settings › Devices: a pairing link minted mid-change ────────────────────────────────────

    private fun mintingHost(gate: Gate): Pair<HostStore, Broker> {
        val broker = Broker(
            mapOf(
                "GET /devices" to "[]",
                "POST /devices" to """{"url":"https://host.example/pair?t=one-time-link","name":"Work laptop"}""",
            ),
            gates = mapOf("POST /devices" to gate),
        )
        return testHostStore(broker.handler) to broker
    }

    private fun ComposeUiTest.mintAPairingLink(gate: Gate) {
        await("devices_add_button")
        onNodeWithTag("devices_add_button").performClick()
        await("devices_add_name")
        onNodeWithTag("devices_add_name").performTextInput("Work laptop")
        onNodeWithTag("devices_add_create").performClick()
        awaitHeld(gate)
    }

    @Test fun a_pairing_link_minted_during_a_recreation_is_shown() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = mintingHost(gate)
        val activity = phoneShell(app, settings = true) { openSettings(SettingsSection.Devices) }
        mintAPairingLink(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The device exists on the broker now and its link opens once: it must be on screen.
        await("devices_pairing_url")
        onNodeWithText("https://host.example/pair?t=one-time-link").assertExists()
        assertEquals(1, broker.count("POST /devices"))
    }

    @Test fun a_pairing_link_minted_during_the_compact_to_wide_swap_is_shown() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = mintingHost(gate)
        val width = flippablePhone(app, settings = true) { openSettings(SettingsSection.Devices) }
        mintAPairingLink(gate)

        unfold(width)
        gate.release.complete(Unit)

        await("devices_pairing_url")
        onNodeWithText("https://host.example/pair?t=one-time-link").assertExists()
        assertEquals(1, broker.count("POST /devices"))
    }

    private fun pairedDeviceHost() = testHostStore(Broker(mapOf("GET /devices" to """[{"name":"Old phone"}]""")).handler)

    private fun ComposeUiTest.askToRevokeTheDevice() {
        await("device_revoke_Old phone")
        onNodeWithTag("device_revoke_Old phone").performClick()
        await("devices_revoke_dialog")
    }

    @Test fun the_revoke_device_confirm_stays_open_through_a_recreation() = runComposeUiTest {
        val activity = phoneShell(pairedDeviceHost(), settings = true) { openSettings(SettingsSection.Devices) }
        askToRevokeTheDevice()

        activity.recreate()

        await("devices_revoke_dialog")
        onNodeWithText("Remove \"Old phone\" from authorized devices?").assertExists()
    }

    @Test fun the_revoke_device_confirm_stays_open_through_the_compact_to_wide_swap() = runComposeUiTest {
        val width = flippablePhone(pairedDeviceHost(), settings = true) { openSettings(SettingsSection.Devices) }
        askToRevokeTheDevice()

        unfold(width)

        await("devices_revoke_dialog")
        onNodeWithText("Remove \"Old phone\" from authorized devices?").assertExists()
    }

    // ── Settings › Worktrees: a batch delete that must not stop halfway ─────────────────────────

    /** 21 clean worktrees: one more than a delete chunk, so the batch takes two requests. */
    private val worktreeIds = (1..21).map { "repo/w$it" }

    private fun worktreesHost(gate: Gate): Pair<HostStore, Broker> {
        val list = worktreeIds.joinToString(",") { """{"id":"$it","path":"/wt/$it","repoName":"repo","hasChanges":false}""" }
        val deleted = worktreeIds.joinToString(",") { """{"id":"$it","ok":true}""" }
        val broker = Broker(
            mapOf(
                "GET /worktrees" to """{"root":"/wt","worktrees":[$list],"orphans":{"count":0},"scanning":false}""",
                "DELETE /worktrees" to """{"results":[$deleted]}""",
            ),
            gates = mapOf("DELETE /worktrees" to gate),
        )
        return testHostStore(broker.handler) to broker
    }

    private fun ComposeUiTest.deleteEveryWorktree(gate: Gate) {
        await("wt_select_clean")
        onNodeWithTag("wt_select_clean").performClick()
        await("wt_delete_bar")
        onNodeWithTag("wt_delete_bar").performClick()
        await("wt_delete_confirm")
        onNodeWithTag("wt_delete_confirm").performClick()
        // The first chunk is at the broker.
        awaitHeld(gate)
    }

    @Test fun a_worktree_batch_delete_finishes_through_a_recreation() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = worktreesHost(gate)
        val activity = phoneShell(app, settings = true) { openSettings(SettingsSection.Worktrees) }
        deleteEveryWorktree(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The second chunk went too, and the batch closed its progress when done.
        waitUntil(timeoutMillis = 10_000) { broker.count("DELETE /worktrees") == 2 }
        awaitGone("wt_delete_progress")
        awaitGone("wt_delete_bar")
    }

    @Test fun a_worktree_batch_delete_finishes_through_the_compact_to_wide_swap() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = worktreesHost(gate)
        val width = flippablePhone(app, settings = true) { openSettings(SettingsSection.Worktrees) }
        deleteEveryWorktree(gate)

        unfold(width)
        gate.release.complete(Unit)

        waitUntil(timeoutMillis = 10_000) { broker.count("DELETE /worktrees") == 2 }
        awaitGone("wt_delete_progress")
        awaitGone("wt_delete_bar")
    }

    // ── Add host: a one-time claim in flight ─────────────────────────────────────────────────────

    private val claim =
        """{"v":1,"action":"pair","hostId":"habcdefghijklmnopqrstuvwxy","name":"Box","relayUrl":"https://h-habc.relay.supermux.dev","claimSecret":"s3cret"}"""

    /** A fleet whose claim (POST /pair/claim) waits at [gate], counted in [claims]. */
    private fun claimingFleet(gate: Gate, claims: AtomicInteger): FleetStore = FleetStore(
        store = testPairedHostStore(),
        scope = TestScope(UnconfinedTestDispatcher()),
        deps = shellTestDeps(),
        appFactory = { _, _, _ -> testHostStore() },
        claimOverride = { _, _, _ ->
            claims.incrementAndGet()
            gate.hold()
            PairClaimResult(host = HostIdentity(hostId = "habcdefghijklmnopqrstuvwxy", name = "Box"), deviceToken = "device-token")
        },
    )

    private fun ComposeUiTest.pasteAClaim(gate: Gate) {
        await("add_host_paste_field")
        onNodeWithTag("add_host_paste_field").performTextInput(claim)
        onNodeWithTag("add_host_paste_submit").performClick()
        awaitHeld(gate)
    }

    private fun FleetStore.hasTheNewHost() = store.list().any { it.hostId == "habcdefghijklmnopqrstuvwxy" }

    @Test fun an_add_host_claim_in_flight_is_redeemed_once_and_kept_through_a_recreation() = runComposeUiTest {
        val gate = Gate()
        val claims = AtomicInteger()
        val fleet = claimingFleet(gate, claims)
        val activity = Recreation(this).also {
            it.setShell(fleet, FakePlatform(), prefs(), wrap = phone()) { openAddHost() }
        }
        pasteAClaim(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The claim was not burned with the old screen: its token is saved, and the screen that is
        // on display now closes as the one that started it would have.
        awaitGone("add_host_paste_field")
        assertTrue(fleet.hasTheNewHost())
        assertEquals(1, claims.get())
    }

    @Test fun an_add_host_claim_in_flight_is_redeemed_once_through_the_compact_to_wide_swap() = runComposeUiTest {
        val gate = Gate()
        val claims = AtomicInteger()
        val fleet = claimingFleet(gate, claims)
        val width = swappableShell(fleet, ShellUiState().apply { openAddHost() }, wrap = phone())
        pasteAClaim(gate)

        unfold(width)
        gate.release.complete(Unit)

        awaitGone("add_host_paste_field")
        assertTrue(fleet.hasTheNewHost())
        assertEquals(1, claims.get())
    }

    // ── The Files pane: a create, a delete and an in-place create in flight ─────────────────────

    /**
     * One workspace showing its Files pane (v2, the tree at /w, holding a.txt), with a.txt also open
     * in a background tab (v3). The host holds its first file op (POST /fs/ops) at [gate].
     */
    private fun filesHost(gate: Gate): Pair<HostStore, Broker> {
        val broker = Broker(
            mapOf(
                "GET /fs/list" to """{"path":"/w","version":"1"}""",
                "GET /workspaces/w1/fs/read" to "hello",
            ),
            gates = mapOf("POST /fs/ops" to gate),
        )
        val app = testHostStore(broker.handler).also { app ->
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                    workspaces = listOf(
                        WorkspaceDto(
                            id = "w1", name = "proj", workdir = "/w",
                            primarySessionId = "s1",
                            layout = LayoutNode.Group("g1", listOf("v2", "v3"), "v2").toDto(),
                            activeViewId = "v2",
                            views = listOf(
                                ViewDto(
                                    id = "v1", workspaceId = "w1", kind = "chat",
                                    state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                                ),
                                ViewDto(
                                    id = "v2", workspaceId = "w1", kind = "editor",
                                    state = JsonObject(mapOf("mode" to JsonPrimitive("tree"))),
                                ),
                                ViewDto(
                                    id = "v3", workspaceId = "w1", kind = "editor",
                                    state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive("a.txt"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        return app to broker
    }

    /** "New file" from the tree's ⋮, named and created in its dialog; the create is at the host. */
    private fun ComposeUiTest.createAFile(gate: Gate) {
        await("tree_menu")
        onNodeWithTag("tree_menu").performClick()
        onNodeWithTag("tree_menu_root_new_file").performClick()
        await("tree_dialog_name")
        onNodeWithTag("tree_dialog_name").performTextInput("notes.md")
        onNodeWithTag("tree_dialog_confirm").performClick()
        awaitHeld(gate)
    }

    @Test fun a_file_created_during_a_recreation_is_finished_by_the_new_dialog() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val activity = phoneShell(app) { selectedId = "s1" }
        createAFile(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The create is finished as if nothing happened: the dialog closes and the file opens.
        awaitGone("tree_dialog_name")
        waitUntil(timeoutMillis = 10_000) { broker.count("POST /workspaces/w1/views") == 1 }
        assertEquals(1, broker.count("POST /fs/ops"))
    }

    @Test fun a_file_created_during_the_compact_to_wide_swap_is_finished() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val width = flippablePhone(app) { selectedId = "s1" }
        createAFile(gate)

        unfold(width)
        gate.release.complete(Unit)

        awaitGone("tree_dialog_name")
        waitUntil(timeoutMillis = 10_000) { broker.count("POST /workspaces/w1/views") == 1 }
        assertEquals(1, broker.count("POST /fs/ops"))
    }

    /**
     * a.txt's row, long pressed for its menu, then Delete and its confirm; the delete is at the host.
     * The tree takes its listing from the host's `fs_dir` frame, sent once the pane has subscribed.
     */
    private fun ComposeUiTest.deleteTheFile(app: HostStore, gate: Gate) {
        await("tree_menu")
        runOnIdle { app.reduce(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "a.txt", type = "file")))) }
        await("tree_row:a.txt")
        onNodeWithTag("tree_row:a.txt").performTouchInput { longClick() }
        await("tree_menu_delete")
        onNodeWithTag("tree_menu_delete").performClick()
        await("tree_dialog_confirm")
        onNodeWithTag("tree_dialog_confirm").performClick()
        awaitHeld(gate)
    }

    @Test fun a_file_deleted_during_a_recreation_closes_its_tab() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val activity = phoneShell(app) { selectedId = "s1" }
        deleteTheFile(app, gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The tab open on the deleted file is closed, as after any delete: left open, its next
        // save would put the file back.
        waitUntil(timeoutMillis = 10_000) { broker.count("DELETE /workspaces/w1/views/v3") == 1 }
        awaitGone("tree_dialog_confirm")
    }

    @Test fun a_file_deleted_during_the_compact_to_wide_swap_closes_its_tab() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val width = flippablePhone(app) { selectedId = "s1" }
        deleteTheFile(app, gate)

        unfold(width)
        gate.release.complete(Unit)

        waitUntil(timeoutMillis = 10_000) { broker.count("DELETE /workspaces/w1/views/v3") == 1 }
        awaitGone("tree_dialog_confirm")
    }

    /** With a hardware keyboard, New file is a field in the tree: named, and committed with Enter. */
    private fun ComposeUiTest.createAFileInPlace(gate: Gate) {
        await("tree_menu")
        onNodeWithTag("tree_menu").performClick()
        onNodeWithTag("tree_menu_root_new_file").performClick()
        await("tree_inline_field")
        onNodeWithTag("tree_inline_field").performTextInput("notes.md")
        onNodeWithTag("tree_inline_field").performImeAction()
        awaitHeld(gate)
    }

    @Test fun a_file_created_in_place_during_a_recreation_is_finished() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val activity = phoneShell(app, keyboard = true) { selectedId = "s1" }
        createAFileInPlace(gate)

        activity.recreate()
        gate.release.complete(Unit)

        awaitGone("tree_inline_field")
        waitUntil(timeoutMillis = 10_000) { broker.count("POST /workspaces/w1/views") == 1 }
        assertEquals(1, broker.count("POST /fs/ops"))
    }

    @Test fun a_file_created_in_place_during_the_compact_to_wide_swap_is_finished() = runComposeUiTest {
        val gate = Gate()
        val (app, broker) = filesHost(gate)
        val width = flippablePhone(app, keyboard = true) { selectedId = "s1" }
        createAFileInPlace(gate)

        unfold(width)
        gate.release.complete(Unit)

        awaitGone("tree_inline_field")
        waitUntil(timeoutMillis = 10_000) { broker.count("POST /workspaces/w1/views") == 1 }
        assertEquals(1, broker.count("POST /fs/ops"))
    }

    // ── One level down: the Finish flow ──────────────────────────────────────────────────────────
    //
    // Only Android's session-only chat draws the Finish button (a workspace chat's header has no
    // slot for it), so the header button and its flow run here on their own, inside the fleet's
    // retained state, as that chat draws them.

    private val branchSession = SessionInfo(id = "s1", name = "feature", workdir = "/w", agent = "claude", session_branch = "feat/x")

    /** A finish that stopped on uncommitted changes: the flow asks for a commit message. */
    private val uncommitted = FinishJobDto(
        sessionId = "s1", action = "merge", status = "failed",
        outcome = FinishResult(status = "uncommitted", files = listOf("a.kt")),
    )

    /** A finish that stopped because the repo has no verify.sh: the flow offers to generate one. */
    private val noVerify = FinishJobDto(sessionId = "s1", action = "merge", status = "failed", outcome = FinishResult(status = "no_verify"))

    @Composable
    private fun FinishHeader(job: FinishJobDto, suggest: suspend () -> VerifySuggestResult? = { null }) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            FinishHeaderButton(
                session = branchSession,
                bindings = FinishBindings(
                    job = job,
                    readiness = { null },
                    finish = { _, _, _, _, _ -> },
                    clearJob = {},
                    verifySuggest = suggest,
                    verifySave = { null },
                    sendToAgent = {},
                ),
            )
        }
    }

    private fun ComposeUiTest.writeACommitMessage() {
        onNodeWithTag("finish_button").performClick()
        await("finish_commit_message")
        onNodeWithTag("finish_commit_message").performTextReplacement("Fix the login bug")
        waitForIdle()
    }

    @Test fun the_finish_flow_and_its_commit_message_survive_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = prefs()
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { FinishHeader(uncommitted) }
            }
        }
        writeACommitMessage()

        activity.recreate()

        await("finish_commit_message")
        assertEquals("Fix the login bug", onNodeWithTag("finish_commit_message").editableText())
    }

    @Test fun the_finish_flow_and_its_commit_message_survive_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = prefs()
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    // Rebuilt somewhere else in the tree, as the shell's wide layout does.
                    if (wide) Row { FinishHeader(uncommitted) } else Column { FinishHeader(uncommitted) }
                }
            }
        }
        writeACommitMessage()

        wide = true
        waitForIdle()

        // A dialog now, not a sheet, with what was written.
        await("finish_commit_message")
        assertEquals("Fix the login bug", onNodeWithTag("finish_commit_message").editableText())
    }

    private val generatedVerify = VerifySuggestResult(content = "#!/bin/sh\nbun test\n", source = "package.json")

    private fun ComposeUiTest.generateAVerifyScript(gate: Gate) {
        onNodeWithTag("finish_button").performClick()
        awaitText("Generate verify")
        onNodeWithText("Generate verify").performClick()
        awaitHeld(gate)
    }

    @Test fun a_verify_script_generated_during_a_recreation_lands_in_the_flow() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = prefs()
        val gate = Gate()
        val suggest: suspend () -> VerifySuggestResult? = { gate.hold(); generatedVerify }
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { FinishHeader(noVerify, suggest) }
            }
        }
        generateAVerifyScript(gate)

        activity.recreate()
        gate.release.complete(Unit)

        // The flow is open again, and the draft it was generating is in it, ready to edit.
        await("finish_verify_draft")
        assertEquals(generatedVerify.content, onNodeWithTag("finish_verify_draft").editableText())
    }

    @Test fun a_verify_script_generated_during_a_move_to_the_wide_layout_lands_in_the_flow() = runComposeUiTest {
        val retained = retainedUi()
        val platform = FakePlatform()
        val prefs = prefs()
        val gate = Gate()
        val suggest: suspend () -> VerifySuggestResult? = { gate.hold(); generatedVerify }
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { FinishHeader(noVerify, suggest) } else Column { FinishHeader(noVerify, suggest) }
                }
            }
        }
        generateAVerifyScript(gate)

        wide = true
        waitForIdle()
        gate.release.complete(Unit)

        await("finish_verify_draft")
        assertEquals(generatedVerify.content, onNodeWithTag("finish_verify_draft").editableText())
    }

    // ── One level down: a display's VNC password ─────────────────────────────────────────────────
    //
    // A display paints a live RFB socket, which no test has, so its clients here are stopped before
    // they connect. What a rebuilt view gives its new client is read off the client itself, and a
    // password prompt is raised on it as the handshake would raise it. The display is named the way
    // a workspace's display tab names itself, and its streams come from one host connection built
    // outside the content, as a host store's are.

    private val vncStream = DisplayStream(id = "d2", sessionName = "demo", provider = "linux-xvfb", transport = "vnc", status = "running")

    /** Every client the display made, newest last, and the host's live streams they come from. */
    private inner class VncClients {
        val made: MutableList<VncClient> = Collections.synchronizedList(mutableListOf())
        val displays = MutableStateFlow(listOf(vncStream))

        fun connect(streamId: String): VncClient =
            VncClient("ws://test:9898", "t", HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), streamId)
                .also { it.stop() }
                .also { made += it }

        fun last(): VncClient = made.last()

        fun actions(displays: StateFlow<List<DisplayStream>> = this.displays) =
            DisplayActions(displays = displays, listDisplays = { listOf(vncStream) }, connectVnc = ::connect)
    }

    /** The password the client will authenticate with (private to it). */
    private fun VncClient.password(): String? =
        VncClient::class.java.getDeclaredField("password").apply { isAccessible = true }.get(this) as String?

    /** Moves the client to [status], as its RFB handshake or its socket would. */
    @Suppress("UNCHECKED_CAST")
    private fun VncClient.reach(status: VncStatus) {
        val flow = VncClient::class.java.getDeclaredField("_status").apply { isAccessible = true }.get(this) as MutableStateFlow<VncStatus>
        flow.value = status
    }

    /** What the RFB handshake does when the screen wants a password the client does not have. */
    private fun VncClient.askForAPassword() = reach(VncStatus.NEEDS_PASSWORD)

    /** A workspace's display tab (w1's view v3) over [displays], the host connection its streams come from. */
    @Composable
    private fun Display(clients: VncClients, displays: StateFlow<List<DisplayStream>> = clients.displays) {
        val actions = remember(displays) { clients.actions(displays) }
        SupermuxTheme(appearance = AppearanceMode.DARK) { DisplayPanel(sessionName = "demo", actions = actions, retainKey = "w1:v3") }
    }

    private fun ComposeUiTest.awaitClient(clients: VncClients, count: Int) =
        waitUntil(timeoutMillis = 10_000) { clients.made.size == count }

    private fun ComposeUiTest.typeAPassword(clients: VncClients, password: String) {
        awaitClient(clients, 1)
        runOnIdle { clients.last().askForAPassword() }
        await("vnc_password_field")
        onNodeWithTag("vnc_password_field").performTextInput(password)
        waitForIdle()
    }

    @Test fun a_vnc_password_being_typed_survives_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Display(clients) }
            }
        }
        typeAPassword(clients, "hunter2")

        activity.recreate()
        // The new view's client asks too; the prompt comes back with what was typed, and sends it.
        awaitClient(clients, 2)
        runOnIdle { clients.last().askForAPassword() }
        await("vnc_password_field")
        onNodeWithText("Connect").performClick()
        waitForIdle()

        assertEquals("hunter2", clients.last().password())
    }

    @Test fun a_vnc_password_being_typed_survives_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { Display(clients) } else Column { Display(clients) }
                }
            }
        }
        typeAPassword(clients, "hunter2")

        wide = true
        waitForIdle()
        awaitClient(clients, 2)
        runOnIdle { clients.last().askForAPassword() }
        await("vnc_password_field")
        onNodeWithText("Connect").performClick()
        waitForIdle()

        assertEquals("hunter2", clients.last().password())
    }

    @Test fun an_accepted_vnc_password_is_not_asked_for_again_after_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Display(clients) }
            }
        }
        typeAPassword(clients, "hunter2")
        onNodeWithText("Connect").performClick()
        waitForIdle()

        activity.recreate()

        // The rebuilt view's client has it before it asks: the prompt does not come back.
        awaitClient(clients, 2)
        assertEquals("hunter2", clients.last().password())
        onNodeWithTag("vnc_password_field").assertDoesNotExist()
    }

    @Test fun an_accepted_vnc_password_is_not_asked_for_again_after_a_move_to_the_wide_layout() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { Display(clients) } else Column { Display(clients) }
                }
            }
        }
        typeAPassword(clients, "hunter2")
        onNodeWithText("Connect").performClick()
        waitForIdle()

        wide = true
        waitForIdle()

        awaitClient(clients, 2)
        assertEquals("hunter2", clients.last().password())
        onNodeWithTag("vnc_password_field").assertDoesNotExist()
    }

    // ── What forgets a VNC password ──────────────────────────────────────────────────────────────
    //
    // A rotation fix must not change security behavior: the password is kept through a rebuild of
    // a display that is still open (the tests above) and through nothing else. Closing the display,
    // a connection that drops, a password the screen refuses and a host added again all forget it,
    // and the next client asks, as it did before #89 kept a copy.

    /** Sends [password] through the prompt, as the user would. */
    private fun ComposeUiTest.sendAPassword(clients: VncClients, password: String) {
        typeAPassword(clients, password)
        onNodeWithText("Connect").performClick()
        waitForIdle()
    }

    /** Client number [count] has no password to give the screen, so it asks, on an empty prompt. */
    private fun ComposeUiTest.assertTheNewClientAsks(clients: VncClients, count: Int) {
        awaitClient(clients, count)
        assertEquals(null, clients.last().password())
        runOnIdle { clients.last().askForAPassword() }
        await("vnc_password_field")
        assertEquals("", onNodeWithTag("vnc_password_field").editableText())
    }

    @Test fun a_vnc_password_is_asked_for_again_after_the_display_is_closed_and_reopened() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var open by mutableStateOf(true)
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { if (open) Display(clients) }
            }
        }
        sendAPassword(clients, "hunter2")

        open = false
        waitForIdle()
        open = true
        waitForIdle()

        assertTheNewClientAsks(clients, 2)
    }

    @Test fun a_vnc_password_closed_before_a_recreation_is_asked_for_again_after_it() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var open by mutableStateOf(true)
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { if (open) Display(clients) }
            }
        }
        sendAPassword(clients, "hunter2")
        open = false
        waitForIdle()

        activity.recreate()
        open = true
        waitForIdle()

        assertTheNewClientAsks(clients, 2)
    }

    @Test fun the_displays_viewer_asks_again_after_it_is_closed_and_reopened() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    val actions = remember { clients.actions() }
                    SupermuxTheme(appearance = AppearanceMode.DARK) { DisplaysScreen(actions = actions) }
                }
            }
        }
        await("displays_row_d2")
        onNodeWithTag("displays_row_d2").performClick()
        sendAPassword(clients, "hunter2")

        onNodeWithTag("display_viewer_close").performClick()
        waitForIdle()
        onNodeWithTag("displays_row_d2").performClick()
        waitForIdle()

        assertTheNewClientAsks(clients, 2)
    }

    @Test fun a_vnc_password_is_asked_for_again_after_the_connection_drops() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Display(clients) }
            }
        }
        sendAPassword(clients, "hunter2")
        // The screen took it; later the connection went (the client reconnects on its own copy).
        runOnIdle { clients.last().reach(VncStatus.CONNECTED) }
        waitForIdle()
        runOnIdle { clients.last().reach(VncStatus.DISCONNECTED) }
        waitForIdle()

        activity.recreate()

        assertTheNewClientAsks(clients, 2)
    }

    @Test fun a_refused_vnc_password_is_not_handed_to_the_view_the_swap_builds() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var wide by mutableStateOf(false)
        setContent {
            val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            PlatformTestProviders(platform, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) {
                    if (wide) Row { Display(clients) } else Column { Display(clients) }
                }
            }
        }
        sendAPassword(clients, "wrong")
        // The screen refused it: the client reconnects, and the handshake asks again.
        runOnIdle { clients.last().reach(VncStatus.CONNECTING) }
        waitForIdle()
        runOnIdle { clients.last().askForAPassword() }
        waitForIdle()

        wide = true
        waitForIdle()

        assertTheNewClientAsks(clients, 2)
    }

    @Test fun a_vnc_password_is_not_handed_to_a_host_added_again_during_a_recreation() = runComposeUiTest {
        val retained = retainedUi()
        val clients = VncClients()
        val platform = FakePlatform()
        val prefs = prefs()
        var host by mutableStateOf<StateFlow<List<DisplayStream>>>(clients.displays)
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                CompositionLocalProvider(LocalRetainedUiState provides retained) { Display(clients, host) }
            }
        }
        sendAPassword(clients, "hunter2")

        // The host was forgotten and paired again: the same display ids, from a new host store.
        host = MutableStateFlow(listOf(vncStream))
        activity.recreate()

        assertTheNewClientAsks(clients, 2)
    }

    // ── One level down: the onboarding intro's QR scan ───────────────────────────────────────────
    //
    // The intro is shown before there is a shell. Its scan opens the camera; on Android a recreation
    // kills the call waiting for the decode, and the platform parks the decode for whoever collects
    // it, as [ParkingCamera] does here. The intro runs infinite animations, so the clock is frozen
    // and advanced by hand ([settle]), as IntroTest does.

    /** A camera whose decode never returns to its caller: it is parked, as Android parks a late one. */
    private class ParkingCamera : FakePlatform(caps = NO_CAPS.copy(camera = true)) {
        private val parked = MutableStateFlow<String?>(null)

        override suspend fun scanQr(): String? {
            scans++
            awaitCancellation()
        }

        override fun pendingScans(): Flow<String> = parked.filterNotNull().map { decoded ->
            parked.compareAndSet(decoded, null)
            decoded
        }

        fun decode(value: String) {
            parked.value = value
        }
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.scanFromTheConnectPage() {
        settle()
        onNodeWithTag("intro_day_rail_3").performClick()
        settle()
        onNodeWithTag("intro_scan").performClick()
        settle()
    }

    @Test fun a_scan_that_decodes_after_a_recreation_reaches_the_intro() = runComposeUiTest {
        mainClock.autoAdvance = false
        val camera = ParkingCamera()
        val pairing = testPairing()
        val prefs = prefs()
        val activity = Recreation(this)
        activity.setContent {
            PlatformTestProviders(camera, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch, uiPrefs = prefs) {
                OnboardingFlow(pairing = pairing, onPaired = {})
            }
        }
        scanFromTheConnectPage()

        activity.recreate()
        settle()
        onNodeWithTag("pair_tofu_dialog").assertDoesNotExist()
        camera.decode(CLAIM_QR)
        settle()

        // The recreated intro takes the parked decode as the scan button would have.
        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
        assertEquals(1, camera.scans)
    }

    @Test fun a_scan_that_decodes_after_the_compact_to_wide_swap_reaches_the_intro() = runComposeUiTest {
        mainClock.autoAdvance = false
        val camera = ParkingCamera()
        val pairing = testPairing()
        val prefs = prefs()
        var width by mutableStateOf(WindowWidthClass.Compact)
        setContent {
            PlatformTestProviders(camera, pointer = false, widthClass = width, inputMode = InputMode.Touch, uiPrefs = prefs) {
                OnboardingFlow(pairing = pairing, onPaired = {})
            }
        }
        scanFromTheConnectPage()

        width = WindowWidthClass.Expanded
        settle()
        camera.decode(CLAIM_QR)
        settle()

        onNodeWithTag("pair_tofu_dialog").assertIsDisplayed()
        assertEquals(1, camera.scans)
    }
}
