// The service that keeps the app's audio going in the background (#118 car 3a).
package dev.supermux.android.platform

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.supermux.android.MainActivity

/**
 * The app's one media session, on its own ExoPlayer: what [AndroidAudioOutputs] plays. Media3 posts the media
 * notification (title, chat, ▶/❚❚, the seek bar) and the lock screen's controls from the session, and keeps this
 * service in the foreground while it plays, so a voice note goes on with supermux in the background.
 *
 * ExoPlayer handles the audio focus (a call or another app's music pauses it) and pauses when the headphones come
 * out. Swiping supermux away from Recents stops it: it plays on unless the app is closed.
 */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setId("supermux-audio")
            .setSessionActivity(open)
            .build()
    }

    /** The app's own controller and the system's (the notification, the lock screen, a headset, a watch). */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** supermux swiped away from Recents: the sound stops with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.let {
            it.stop()
            it.clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}
