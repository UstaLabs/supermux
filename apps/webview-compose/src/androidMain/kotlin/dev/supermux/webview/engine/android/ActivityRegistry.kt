package dev.supermux.webview.engine.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The Activities that show web views, held weakly (spec §7: "the Android WebView composable registers its Activity with
 * the runtime, held weakly"). Engines are built for the one that registered last and is still alive, and hear when an
 * Activity is destroyed, so none keeps a dead one. Main thread only.
 */
internal object ActivityRegistry {
    private val activities = ArrayList<WeakReference<Activity>>()
    private val listeners = ArrayList<(Activity) -> Unit>()
    private var watched: Application? = null

    /** [activity] shows a web view. The first registration also starts WebView's startup. */
    fun register(activity: Activity) {
        activities.removeAll { it.get().let { known -> known == null || known === activity } }
        activities += WeakReference(activity)
        val app = activity.application
        if (watched !== app) {
            watched?.unregisterActivityLifecycleCallbacks(lifecycle)
            app.registerActivityLifecycleCallbacks(lifecycle)
            watched = app
        }
        WebViewStartup.begin(activity.applicationContext)
    }

    /** The Activity that registered last and is still alive, or null. */
    fun current(): Activity? = activities.asReversed().firstNotNullOfOrNull { reference ->
        reference.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    /** Calls [listener] whenever an Activity is destroyed. Returns the function that stops it. */
    fun onDestroyed(listener: (Activity) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            activities.removeAll { it.get().let { known -> known == null || known === activity } }
            listeners.toList().forEach { runCatching { it(activity) } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
