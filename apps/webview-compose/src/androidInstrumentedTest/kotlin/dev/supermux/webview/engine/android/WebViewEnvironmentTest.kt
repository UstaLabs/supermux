package dev.supermux.webview.engine.android

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The phone's WebView has every feature the engine needs (Task 6). Its log line records what it runs on. */
@RunWith(AndroidJUnit4::class)
class WebViewEnvironmentTest {
    @Test fun theSystemWebViewHasEveryFeatureTheEngineNeeds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var report = ""
        var missing = emptyList<String>()
        instrumentation.runOnMainSync {
            val webView = WebViewCompat.getCurrentWebViewPackage(instrumentation.targetContext)
            assertNotNull(webView, "a WebView provider")
            val required = listOf(
                WebViewFeature.WEB_MESSAGE_LISTENER,
                WebViewFeature.DOCUMENT_START_SCRIPT,
                WebViewFeature.VISUAL_STATE_CALLBACK,
                WebViewFeature.NAVIGATION_LISTENER,
                WebViewFeature.MULTI_PROFILE,
            )
            val optional = listOf(
                WebViewFeature.SAVE_STATE,
                WebViewFeature.ALGORITHMIC_DARKENING,
                WebViewFeature.BACK_FORWARD_CACHE,
                WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE,
                WebViewFeature.WEB_VIEW_RENDERER_TERMINATE,
            )
            val supported = (required + optional).associateWith { WebViewFeature.isFeatureSupported(it) }
            missing = required.filterNot { supported.getValue(it) }
            report = "${webView.packageName} ${webView.versionName} on API ${Build.VERSION.SDK_INT}: $supported"
        }
        Log.i("WebViewEnvironment", report)
        assertEquals(emptyList(), missing, "features the engine needs: $report")
    }
}
