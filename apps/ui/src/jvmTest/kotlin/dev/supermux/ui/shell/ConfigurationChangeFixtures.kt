// What the configuration-change suites share (card #89): a recreation staged by hand, the shell at a
// width a test can flip, and the one-chat workspace most of them start from.
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.FleetSettingsExtra
import dev.supermux.ui.settings.FleetSettingsSection
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an Android configuration change does to a composition: all of it is disposed, and the new one
 * gets back only what lives outside it (here, what a test builds before `setContent`: the fleet,
 * the platform, the prefs) and its `rememberSaveable` state, saved and restored by key. Plain
 * `remember` is gone. (`StateRestorationTester` stages the same thing, but its desktop
 * `emulateSaveAndRestore` is a `TODO()` in CMP 1.12.)
 */
@OptIn(ExperimentalTestApi::class)
internal class Recreation(private val test: ComposeUiTest) {
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var shown by mutableStateOf(true)
    /** How many times the content left the composition: a [recreate] must add exactly one. */
    private var disposals = 0

    fun setContent(content: @Composable () -> Unit) = test.setContent {
        // The same call site before and after, so every rememberSaveable finds its key again.
        if (shown) {
            DisposableEffect(Unit) { onDispose { disposals++ } }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = content)
        }
    }

    fun recreate() {
        val saved = test.runOnIdle { registry.performSave() }
        shown = false
        // A frame each way, so the content really goes and comes back even in a test that froze
        // the clock (an endless animation on screen): `waitForIdle` alone advances no frame then,
        // and a "recreation" that never disposed anything passed vacuously.
        val before = disposals
        test.mainClock.advanceTimeByFrame()
        test.waitForIdle()
        check(disposals == before + 1) { "the recreation never disposed the content: nothing was tested" }
        registry = SaveableStateRegistry(saved) { true }
        shown = true
        test.mainClock.advanceTimeByFrame()
        test.waitForIdle()
    }
}

/**
 * The shell as MainActivity holds it, in this recreation: its [ShellUiState] saveable (so the open
 * chat and the routes come back with the activity), started by [initial]; [fleet], [platform] and
 * [prefs] built by the caller, outside the content, as the view model holds them. [wrap] adds the
 * locals a test needs around the shell. [settings] draws the fleet's real Settings pages, as
 * MainActivity does (the shell alone draws an empty page for each).
 */
internal fun Recreation.setShell(
    fleet: FleetStore,
    platform: FakePlatform,
    prefs: UiPrefs,
    widthClass: WindowWidthClass = WindowWidthClass.Compact,
    wrap: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    settings: Boolean = false,
    initial: ShellUiState.() -> Unit = {},
) = setContent {
    PlatformTestProviders(platform, pointer = false, widthClass = widthClass, inputMode = InputMode.Touch, uiPrefs = prefs) {
        wrap {
            val ui = rememberSaveable(saver = ShellUiState.Saver) { ShellUiState().apply(initial) }
            ShellWithSettings(fleet, ui, settings)
        }
    }
}

/** [SupermuxApp], with the fleet's Settings pages when [settings]. */
@Composable
internal fun ShellWithSettings(fleet: FleetStore, ui: ShellUiState, settings: Boolean) {
    if (settings) {
        SupermuxApp(
            fleet = fleet,
            ui = ui,
            settingsSection = { section, scope -> FleetSettingsSection(section, scope, fleet) },
            settingsExtra = { extra, scope -> FleetSettingsExtra(extra, scope) },
        )
    } else {
        SupermuxApp(fleet = fleet, ui = ui)
    }
}

/** A host whose one workspace "proj" (w1) shows one chat, "worker-1" (s1), in view v1. */
internal fun oneChatWorkspace(
    respondTo: MockRequestHandler = { respond("{}", HttpStatusCode.OK) },
): HostStore = testHostStore(respondTo).also { app ->
    app.reduce(
        ServerFrame.Snapshot(
            sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
            workspaces = listOf(
                WorkspaceDto(
                    id = "w1", name = "proj", workdir = "/w",
                    primarySessionId = "s1",
                    layout = singleViewLayout("g1", "v1").toDto(),
                    views = listOf(
                        ViewDto(
                            id = "v1", workspaceId = "w1", kind = "chat",
                            state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                        ),
                    ),
                ),
            ),
        ),
    )
}

internal fun SemanticsNodeInteraction.editableText(): String =
    fetchSemanticsNode().config[SemanticsProperties.EditableText].text

/**
 * The shell at a width the test can flip, the way a phone turned to landscape or a Fold opened
 * flips it — one composition, so this is the swap alone, as iOS gets it. [wrap] adds the locals a
 * test needs around the shell.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.swappableShell(
    fleet: FleetStore,
    ui: ShellUiState,
    platform: FakePlatform = FakePlatform(),
    prefs: UiPrefs = UiPrefs(FakeSettingsStore()),
    wrap: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    settings: Boolean = false,
): MutableState<WindowWidthClass> {
    val width = mutableStateOf(WindowWidthClass.Compact)
    setContent {
        PlatformTestProviders(platform, pointer = false, widthClass = width.value, inputMode = InputMode.Touch, uiPrefs = prefs) {
            wrap { ShellWithSettings(fleet, ui, settings) }
        }
    }
    return width
}
