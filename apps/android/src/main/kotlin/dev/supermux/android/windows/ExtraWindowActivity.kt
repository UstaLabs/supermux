// One extra window on Android: the slice of a workspace a claim in the shared registry
// gives it, drawn by the shared `ExtraWindowPanes` — the same panes desktop's extra `Window {}`
// draws. See `AndroidWindows.kt` for how the windows share state.
package dev.supermux.android.windows

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.android.MainActivity
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.ui.shell.windows.ExtraWindowPanes
import dev.supermux.ui.shell.windows.PersistedWindowHost
import dev.supermux.ui.shell.windows.WindowHost
import dev.supermux.ui.shell.windows.extraWindowTitle
import dev.supermux.ui.shell.windows.tearOutTabFrom
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * An extra window. It owns nothing but its claim: the workspace it draws is composed by the main
 * window, so while the main window is not composed (Android destroyed it, or restored THIS window
 * after process death) it shows a way back instead, and fills in again once the main window does.
 *
 * Its life follows the claim both ways: closing the window gives its views back to the main
 * window, and a claim that goes away (its views were closed) closes the window.
 */
class ExtraWindowActivity : ComponentActivity() {

    /** This window's claim, as last seen — what a restore after process death re-claims. */
    private lateinit var claim: PersistedWindowHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = (savedInstanceState ?: intent.extras)?.let(::readClaim)
        if (restored == null) {
            finish()
            return
        }
        claim = restored
        val windows = AndroidWindows.shellWindows
        // A new process: the registry is empty, so park the claim until the main window composes
        // its workspace (which `AndroidShellWindows` makes it do) and the registry takes it back.
        if (windows.registry.extras().none { it.id == claim.id } && windows.pending.none { it.id == claim.id }) {
            windows.pending = windows.pending + claim
        }
        enableEdgeToEdge()

        val prefs = UiPrefs(AndroidSettingsStore(applicationContext))
        // Blocking for the same reason MainActivity blocks: no first frame in the wrong theme.
        val (appearanceSeed, textScaleSeed) = runBlocking {
            prefs.appearance(AppearanceMode.SYSTEM).first() to prefs.textScale.first()
        }
        val controller = AndroidWindowHostController(this)

        setContent {
            val appearance by prefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by prefs.textScale.collectAsState(textScaleSeed)
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = prefs) {
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ExtraWindowContent(controller)
                }
            }
        }
    }

    @Composable
    private fun ExtraWindowContent(controller: AndroidWindowHostController) {
        val windows = AndroidWindows.shellWindows
        val host = windows.registry.extras().firstOrNull { it.id == claim.id }
        val waiting = host == null && windows.pending.any { it.id == claim.id }
        if (host == null && !waiting) {
            // The claim is gone — its views were closed, or moved back. So is this window.
            LaunchedEffect(Unit) { finish() }
            return
        }
        if (host != null) SideEffect { claim = host.toClaim() }

        val ui = AndroidWindows.mainUi
        val bind = host?.let { ui?.panesBindFor(it.workspaceId) }?.takeIf { it.holders > 0 }
        if (ui == null || host == null || bind == null) {
            MainWindowGone()
            return
        }
        val title = extraWindowTitle(
            bind.current.name,
            ui.windows.layoutFor(host.id, bind.ws.layoutSync.tree),
            bind.ws.viewsById,
        )
        SideEffect {
            setTitle(title)
            setTaskDescription(ActivityManager.TaskDescription(title))
        }
        ExtraWindowPanes(
            hostId = host.id,
            bind = bind,
            ui = ui,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("workspace_layout_host_extra"),
            onTearOutTab = { viewId ->
                tearOutTabFrom(windows.registry, bind, viewId)?.let(controller::open)
            },
        )
    }

    /**
     * The main window is not composed, so there is nothing to draw — say so, and offer the way
     * back. Held back for a moment first: resizing the split recreates the main activity, and this
     * must not flash on every drag of the divider.
     */
    @Composable
    private fun MainWindowGone() {
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(MAIN_WINDOW_GRACE_MS)
            show = true
        }
        if (!show) return
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "This window shows part of a workspace from the main supermux window, which is closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = ::openMainWindow, modifier = Modifier.testTag("extra_window_open_main")) {
                    Text("Open supermux")
                }
            }
        }
    }

    private fun openMainWindow() {
        // NEW_TASK finds the main task by its affinity (this window's task has its own, see the
        // manifest), and LAUNCH_ADJACENT puts it back beside this one.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::claim.isInitialized) writeClaim(outState, claim)
    }

    override fun onDestroy() {
        // Closing the window (not a rotation or a resize) gives its views back to the main one.
        if (isFinishing && ::claim.isInitialized) {
            val windows = AndroidWindows.shellWindows
            windows.registry.unclaim(claim.id)
            windows.pending = windows.pending.filterNot { it.id == claim.id }
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST_ID = "dev.supermux.window.HOST_ID"
        private const val EXTRA_WORKSPACE_ID = "dev.supermux.window.WORKSPACE_ID"
        private const val EXTRA_CLAIMED_VIEW_IDS = "dev.supermux.window.CLAIMED_VIEW_IDS"
        private const val MAIN_WINDOW_GRACE_MS = 1_500L

        fun intent(context: Context, host: WindowHost): Intent =
            Intent(context, ExtraWindowActivity::class.java)
                .addFlags(EXTRA_WINDOW_FLAGS)
                .putExtras(Bundle().also { writeClaim(it, host.toClaim()) })

        private fun WindowHost.toClaim() = PersistedWindowHost(
            id = id,
            workspaceId = workspaceId,
            claimedViewIds = claimedViewIds.toList(),
            // An Android window's bounds are the system's; the registry only needs a placeholder.
            x = 0f, y = 0f, width = 0f, height = 0f,
        )

        internal fun writeClaim(out: Bundle, claim: PersistedWindowHost) {
            out.putString(EXTRA_HOST_ID, claim.id)
            out.putString(EXTRA_WORKSPACE_ID, claim.workspaceId)
            out.putStringArray(EXTRA_CLAIMED_VIEW_IDS, claim.claimedViewIds.toTypedArray())
        }

        internal fun readClaim(from: Bundle): PersistedWindowHost? {
            val id = from.getString(EXTRA_HOST_ID) ?: return null
            val workspaceId = from.getString(EXTRA_WORKSPACE_ID) ?: return null
            return PersistedWindowHost(
                id = id,
                workspaceId = workspaceId,
                claimedViewIds = from.getStringArray(EXTRA_CLAIMED_VIEW_IDS)?.toList().orEmpty(),
                x = 0f, y = 0f, width = 0f, height = 0f,
            )
        }
    }
}
