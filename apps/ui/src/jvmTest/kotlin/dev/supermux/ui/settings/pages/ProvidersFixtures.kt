package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountIdentityDto
import dev.supermux.net.AccountUsageWindowDto
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

// Shared by the Providers & accounts page tests (#97): today's DTO shapes, the two pages mounted the way the hub
// mounts them, and a HostStore over a mocked broker.

/** Every provider installed and signed out. */
internal fun allInstalled() = listOf("claude", "codex", "opencode", "cursor", "grok", "antigravity")
    .map { AgentInstallStatus(kind = it, installed = true, authed = false) }

internal fun claudeInstalledSignedOut() = listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))

internal fun systemAccount(agent: String, email: String? = null) = AccountDto(
    id = "$agent:system", agent = agent, method = "system", label = "System login", system = true,
    identity = email?.let { AccountIdentityDto(email = it) }, createdAt = "1970-01-01T00:00:00.000Z",
)

internal fun workAccountWithUsage() = AccountDto(
    id = "work", agent = "claude", method = "subscription", label = "Work (Max)",
    identity = AccountIdentityDto(email = "me@acme.dev"), createdAt = "2026-10-01T00:00:00.000Z",
    usage = listOf(AccountUsageWindowDto("five_hour", 62.0), AccountUsageWindowDto("seven_day", 18.0)),
)

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitTag(tag: String, timeout: Long = 5_000) =
    waitUntil(timeoutMillis = timeout) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitText(text: String, substring: Boolean = false, timeout: Long = 5_000) =
    waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

/** One provider's page, with its holders remembered above it as the hub's Providers section does. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setProviderPage(
    agent: String,
    agents: AgentSettingsActions = AgentSettingsActions(agentStatuses = { allInstalled() }),
    accounts: AccountsActions = AccountsActions(),
    platform: FakePlatform = FakePlatform(),
    widthClass: WindowWidthClass = WindowWidthClass.Expanded,
    onBack: () -> Unit = {},
    mounted: () -> Boolean = { true },
) = setPlatformContent(platform = platform, widthClass = widthClass, pointer = widthClass != WindowWidthClass.Compact) {
    SupermuxTheme(appearance = AppearanceMode.DARK) {
        if (mounted()) ProviderPageHarness(agent, agents, accounts, onBack)
    }
}

@Composable
internal fun ProviderPageHarness(agent: String, agents: AgentSettingsActions, accounts: AccountsActions, onBack: () -> Unit = {}) {
    val providers = rememberProvidersState(agents)
    ProviderDetailPage(agent, providers, agents, accounts, onBack = onBack)
}

/** The list, with its holders remembered above it. */
@Composable
internal fun ProvidersListHarness(
    agents: AgentSettingsActions,
    accounts: AccountsActions = AccountsActions(),
    onOpen: (String) -> Unit = {},
    anchor: String? = null,
) {
    val providers = rememberProvidersState(agents)
    ProvidersPage(providers, accounts, onOpenProvider = onOpen, anchor = anchor)
}

internal fun appForAgents(
    statusJson: String? = """[{"kind":"claude","installed":true,"authed":true},{"kind":"codex","installed":true,"authed":false}]""",
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    loginJson: String = """{"kind":"claude","phase":"awaiting_user","url":"https://auth.example","code":"XYZ"}""",
    installJson: String = """{"state":"running","log":"installing"}""",
    mutationStatus: HttpStatusCode = HttpStatusCode.OK,
    baseUrl: String = "ws://test:9898",
): HostStore {
    val engine = MockEngine { req ->
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val path = req.url.encodedPath
        when {
            req.method == HttpMethod.Get && path == "/agents/status" ->
                if (statusJson == null) respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                else respond(statusJson, statusCode, jsonHeaders)
            path.endsWith("/login") && req.method == HttpMethod.Post -> respond(loginJson, HttpStatusCode.OK, jsonHeaders)
            path.endsWith("/login") && req.method == HttpMethod.Get -> respond(loginJson, HttpStatusCode.OK, jsonHeaders)
            path.endsWith("/install") -> respond(installJson, HttpStatusCode.OK, jsonHeaders)
            path == "/opencode/providers" -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            // Mutation endpoints honour [mutationStatus], so a non-2xx is proven through HostStore + BrokerApi.
            path == "/settings/config" && req.method == HttpMethod.Put -> respond("{}", mutationStatus, jsonHeaders)
            path == "/opencode/auth/key" && req.method == HttpMethod.Post -> respond("{}", mutationStatus, jsonHeaders)
            path == "/opencode/auth/oauth/finish" && req.method == HttpMethod.Post -> respond("{}", mutationStatus, jsonHeaders)
            path.endsWith("/login/code") && req.method == HttpMethod.Post -> respond("{}", mutationStatus, jsonHeaders)
            else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
        }
    }
    val api = BrokerApi(baseUrl, "t", HttpClient(engine))
    return HostStore(
        baseUrl = baseUrl,
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        deps = HostStoreDeps(httpFactory = { HttpClient(engine) }, settings = FakeSettingsStore(), clock = FixedClock()),
        connectOnInit = false,
        sendFrameOverride = { },
        apiOverride = api,
    )
}

/** [AgentSettingsActions] over a real [HostStore]. */
internal fun hostActions(app: HostStore, onSave: (Boolean) -> Unit = {}) = AgentSettingsActions(
    agentStatuses = { app.agentStatuses() },
    agentStartLogin = { app.startAgentLogin(it) },
    agentPollLogin = { app.agentLoginState(it) },
    agentSendCode = { k, c -> app.sendAgentLoginCode(k, c) },
    agentCancelLogin = { app.cancelAgentLogin(it) },
    agentSaveSecret = { k, v -> app.saveAgentSecret(k, v).also(onSave) },
    agentStartInstall = { app.startAgentInstall(it) },
    agentPollInstall = { app.agentInstallState(it) },
    openCodeProviders = { app.openCodeProviders() },
    openCodeSetKey = { id, key -> app.setOpenCodeKey(id, key) },
    openCodeStartOAuth = { id, m -> app.startOpenCodeOAuth(id, m) },
    openCodeFinishOAuth = { id, m, c -> app.finishOpenCodeOAuth(id, m, c) },
)
