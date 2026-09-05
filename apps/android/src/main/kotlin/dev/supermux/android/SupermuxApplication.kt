package dev.supermux.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import dev.supermux.android.platform.AndroidTts

/**
 * Ensures WebView uses an isolated data dir before any editor WebView is created, and owns the
 * process-wide read-aloud lifetime.
 *
 * `onTerminate` is never called on a real device, so the TTS engine is released when the LAST
 * activity is destroyed instead: `Platform.tts` is a process singleton (a rotation must not orphan
 * a talking engine), which means nothing else is positioned to hand its `TextToSpeech` service
 * connection back. A rotation ALSO drops the count to zero (the old activity is destroyed before
 * the new one is created), so `isChangingConfigurations` is the load-bearing half of the test —
 * without it, rotating mid-sentence would silence the reader.
 */
class SupermuxApplication : Application() {

    /** Live activity count. Only ever touched on the main thread by the framework callbacks. */
    private var activities = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("supermux")
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activities++ }
            override fun onActivityDestroyed(activity: Activity) {
                activities--
                // Zero live activities AND not on the way to being re-created: the app is going
                // away, so the process-wide engine's service connection goes back.
                if (activities <= 0 && !activity.isChangingConfigurations) AndroidTts.shutdown()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
        })
    }
}
