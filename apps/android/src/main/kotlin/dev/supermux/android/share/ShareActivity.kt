// #167: what opens when the user shares from another app to supermux (or straight to an agent from the Direct Share
// row): the share sheet over that app, then — once sent — a bar at the bottom ("Sent to Tally · Open chat") while the
// user is back in the app they shared from.
package dev.supermux.android.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.supermux.android.MainActivity
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.proto.SessionInfo
import dev.supermux.share.ShareItem
import dev.supermux.share.ShareJob
import dev.supermux.share.ShareTarget
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.session.rememberAgentAvatars
import dev.supermux.ui.share.SharePreview
import dev.supermux.ui.share.ShareResultBar
import dev.supermux.ui.share.ShareSheet
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ShareActivity : ComponentActivity() {
    private data class Outcome(val text: String, val sessionId: String?, val queued: Boolean)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedContent(intent)
        if (shared == null) {
            android.widget.Toast.makeText(this, localized(Str.share_nothing), android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val jobId = savedInstanceState?.getString("job") ?: ShareJob.newId(System.currentTimeMillis())
        val targets = ShareQueue.targets(applicationContext)
        val shortcutId = if (Build.VERSION.SDK_INT >= 29) intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) else null
        val preselected = ShareQueue.fromShortcutId(shortcutId, targets)
        val prefs = UiPrefs(AndroidSettingsStore(applicationContext))
        val (appearanceSeed, textScaleSeed) = runBlocking { prefs.appearance(AppearanceMode.SYSTEM).first() to prefs.textScale.first() }
        savedJobId = jobId

        setContent {
            val appearance by prefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by prefs.textScale.collectAsState(textScaleSeed)
            // The files are copied in at once (the sharing app's grant ends with this screen); Send waits for them.
            var items by remember { mutableStateOf<List<SharePreview>?>(null) }
            var sending by remember { mutableStateOf(false) }
            var outcome by remember { mutableStateOf<Outcome?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                val files = ShareQueue.copyIn(applicationContext, jobId, shared.uris, shared.type)
                val texts = shared.texts.map { ShareItem(text = it) }
                items = withContext(Dispatchers.IO) { (texts + files).map { SharePreview(it, thumbnail(it)?.asImageBitmap()) } }
            }
            val http = remember { HttpClient(CIO) }
            DisposableEffect(Unit) { onDispose { http.close() } }
            val avatars = rememberAgentAvatars(
                sessions = targets.filter { it.isAgent && it.avatar != null }.map { SessionInfo(id = it.sessionId, name = it.name, workdir = "", agent = "", role = "personal_assistant", avatar = it.avatar) },
                apiFor = { id -> targets.firstOrNull { it.sessionId == id }?.let { ShareQueue.hostApi(applicationContext, it.hostId, http) } },
            )
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = prefs) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val o = outcome
                    if (o == null) {
                        ShareSheet(
                            items = items.orEmpty(),
                            targets = targets,
                            preselected = preselected,
                            avatars = avatars,
                            sending = sending || items == null,
                            onClose = { cancel(jobId) },
                            onSend = { target, message ->
                                sending = true
                                scope.launch { outcome = send(jobId, target, message, items.orEmpty().map { it.item }) }
                            },
                            modifier = Modifier.padding(top = 56.dp),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            ShareResultBar(
                                text = o.text,
                                action = if (o.sessionId != null) localized(Str.share_open_chat) else localized(Str.share_ok),
                                queued = o.queued,
                                onAction = {
                                    o.sessionId?.let { sid ->
                                        startActivity(Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(SupermuxMessagingService.EXTRA_SESSION_ID, sid))
                                    }
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                            )
                        }
                        LaunchedEffect(o) {
                            showAsBar()
                            delay(4_000)
                            finish()
                        }
                    }
                }
            }
        }
    }

    private var savedJobId: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedJobId?.let { outState.putString("job", it) }
    }

    private fun cancel(jobId: String) {
        lifecycleScope.launch(Dispatchers.IO) { File(filesDir, "share/$jobId").deleteRecursively() }
        finish()
    }

    /** Queues the share and waits a little for it to go: "Sent" when it did, "Queued" when it will once online. */
    private suspend fun send(jobId: String, target: ShareTarget, message: String, items: List<ShareItem>): Outcome {
        val job = ShareJob(jobId, target.hostId, target.sessionId, target.name, message, items, System.currentTimeMillis())
        val workId = ShareQueue.enqueue(applicationContext, job)
        if (!online()) return Outcome(localized(Str.share_queued, target.name), null, queued = true)
        val done = withTimeoutOrNull(12_000) {
            WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(workId).first { it == null || it.state.isFinished }
        }
        return when (done?.state) {
            WorkInfo.State.SUCCEEDED -> Outcome(localized(Str.share_sent, target.name), target.sessionId, queued = false)
            WorkInfo.State.FAILED -> Outcome(localized(Str.share_failed, target.name, done.outputData.getString(ShareWorker.KEY_ERROR).orEmpty()), null, queued = false)
            // Still going (a big video) or waiting to retry: the worker carries on without this screen.
            else -> Outcome(localized(Str.share_queued, target.name), null, queued = true)
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** From the full sheet to a bar at the bottom: the app behind takes every touch outside it again. */
    private fun showAsBar() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun thumbnail(item: ShareItem): Bitmap? {
        val path = item.path ?: return null
        val mime = item.mime.orEmpty()
        return runCatching {
            when {
                mime.startsWith("image/") -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                mime.startsWith("video/") && Build.VERSION.SDK_INT >= 29 -> ThumbnailUtils.createVideoThumbnail(File(path), Size(160, 160), null)
                else -> null
            }
        }.getOrNull()
    }

    private class Shared(val texts: List<String>, val uris: List<Uri>, val type: String?)

    /** ACTION_SEND / SEND_MULTIPLE: the words or link (with its subject, when the app gave one) and the files. */
    @Suppress("DEPRECATION")
    private fun sharedContent(intent: Intent?): Shared? {
        intent ?: return null
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return null
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val texts = listOfNotNull(
            subject?.takeIf { it.isNotEmpty() && text?.contains(it) != true },
            text?.takeIf { it.isNotEmpty() },
        )
        if (texts.isEmpty() && uris.isEmpty()) return null
        return Shared(texts, uris, intent.type)
    }
}
