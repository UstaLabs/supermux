// One extra window on Android: the slice of a workspace a claim in the shared registry gives it.
// The screen itself is the shared `ExtraWindowHost` (an iPad scene draws the same one); this is
// the activity around it. See `AndroidWindows.kt` for how the windows share state.
package dev.supermux.android.windows

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.supermux.android.MainActivity
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.shell.windows.ExtraWindowHost
import dev.supermux.ui.shell.windows.ExtraWindows
import dev.supermux.ui.shell.windows.PersistedWindowHost
import dev.supermux.ui.shell.windows.WindowHost
import dev.supermux.ui.shell.windows.toClaim
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * An extra window. Its life follows the claim both ways: closing the window gives its views back
 * to the main window, and a claim that goes away (its views were closed) closes the window.
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
        ExtraWindows.adopt(AndroidWindows.shellWindows, claim)
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
                    ExtraWindowHost(
                        hostId = claim.id,
                        windows = AndroidWindows.shellWindows,
                        mainUi = AndroidWindows.mainUi,
                        onClaim = { claim = it },
                        onClaimGone = ::finish,
                        onTitle = { title ->
                            setTitle(title)
                            setTaskDescription(ActivityManager.TaskDescription(title))
                        },
                        onOpenMain = ::openMainWindow,
                        onOpened = controller::open,
                    )
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
        if (isFinishing && ::claim.isInitialized) ExtraWindows.close(AndroidWindows.shellWindows, claim.id)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST_ID = "dev.supermux.window.HOST_ID"
        private const val EXTRA_WORKSPACE_ID = "dev.supermux.window.WORKSPACE_ID"
        private const val EXTRA_CLAIMED_VIEW_IDS = "dev.supermux.window.CLAIMED_VIEW_IDS"

        fun intent(context: Context, host: WindowHost): Intent =
            Intent(context, ExtraWindowActivity::class.java)
                .addFlags(EXTRA_WINDOW_FLAGS)
                .putExtras(Bundle().also { writeClaim(it, host.toClaim()) })

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
