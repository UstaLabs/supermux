package dev.supermux.android

import android.app.Application
import android.os.Build
import android.webkit.WebView

/** Ensures WebView uses an isolated data dir before any editor WebView is created. */
class SupermuxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("supermux")
        }
    }
}
