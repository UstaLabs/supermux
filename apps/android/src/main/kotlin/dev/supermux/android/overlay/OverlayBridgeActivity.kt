package dev.supermux.android.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.supermux.ui.platform.PickKind

/**
 * The assistant overlay's way to the things only an activity may ask for (#62): the screen-sharing
 * consent behind looks (drag it onto something and hold still), and the file and photo pickers behind
 * the chat's attach button. A service cannot launch either for a result.
 *
 * Invisible (a translucent theme, nothing drawn) and gone as soon as it has an answer. The overlay
 * steps out of the way while it is up: its windows would cover the system's dialog and picker.
 */
class OverlayBridgeActivity : ComponentActivity() {

    companion object {
        /** The overlay's request this answers (set by the overlay when it starts the bridge). */
        const val EXTRA_REQUEST = "dev.supermux.overlay.request"
        private const val EXTRA_JOB = "dev.supermux.overlay.job"
        private const val EXTRA_KIND = "dev.supermux.overlay.kind"
        private const val JOB_SHARE = "share"
        private const val JOB_PICK = "pick"
        private const val MAX_PICKED_MEDIA = 20

        fun share(context: Context): Intent = intent(context).putExtra(EXTRA_JOB, JOB_SHARE)

        fun pick(context: Context, kind: PickKind): Intent =
            intent(context).putExtra(EXTRA_JOB, JOB_PICK).putExtra(EXTRA_KIND, kind.name)

        // A task of its own every time: one left behind (a picker walked away from) is never reused.
        private fun intent(context: Context) = Intent(context, OverlayBridgeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    /** The overlay has had its answer (or its "no"); it must never be left waiting. */
    private var answered = false
    private val request: Int get() = intent.getIntExtra(EXTRA_REQUEST, -1)

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        answered = true
        val data = result.data
        // Answered while this is still on screen: the overlay may take its screen-capture service type
        // now, and Android 14+ wants that type before the projection starts.
        if (result.resultCode == Activity.RESULT_OK && data != null) AssistantOverlayService.shareAllowed(request, result.resultCode, data)
        else AssistantOverlayService.shareRefused(request)
        finish()
    }

    // Documents, not GetContent: their grant can be kept past this activity, and the upload runs on
    // after it is gone.
    private val anyFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it) }
    private val media = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_MEDIA)) { picked(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AssistantOverlayService.bridgeOpened(request, this)) {
            // The overlay stopped waiting for this one (or is gone): nothing to ask.
            answered = true
            finish()
            return
        }
        // Re-created mid-ask: the launcher's answer is still delivered to this new instance.
        if (savedInstanceState != null) return
        val asked = runCatching {
            when (intent.getStringExtra(EXTRA_JOB)) {
                JOB_SHARE -> {
                    val projections = getSystemService(MediaProjectionManager::class.java)
                    // The whole screen (a look frames part of it), not a list of apps to switch to.
                    consent.launch(
                        if (Build.VERSION.SDK_INT >= 34) projections.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                        else projections.createScreenCaptureIntent(),
                    )
                }
                JOB_PICK -> when (runCatching { PickKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrDefault(PickKind.Any)) {
                    PickKind.Any -> anyFile.launch(arrayOf("*/*"))
                    PickKind.Images -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    PickKind.Media -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                else -> error("no job")
            }
        }.isSuccess
        if (!asked) {
            giveUp()
            finish()
        }
    }

    private fun picked(uris: List<Uri>) {
        answered = true
        // A picked file's read grant belongs to this activity and ends with it: keep it where the
        // provider allows (documents, the photo picker), so the upload can finish.
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        AssistantOverlayService.picked(request, uris)
        finish()
    }

    /** No answer is coming (it could not ask, or it was closed without one): tell the overlay "no". */
    private fun giveUp() {
        if (answered) return
        answered = true
        when (intent.getStringExtra(EXTRA_JOB)) {
            JOB_SHARE -> AssistantOverlayService.shareRefused(request)
            JOB_PICK -> AssistantOverlayService.picked(request, emptyList())
        }
    }

    override fun onDestroy() {
        if (isFinishing) giveUp()
        super.onDestroy()
    }
}
