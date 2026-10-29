package dev.supermux.webview.engine.ios

import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/*
 * Kotlin/Native iOS tests run on the main thread of a process that isn't an app: no WKWebView loads a page there (the
 * plan's fact F2), and runTest blocks the main queue (F4). So these tests pump the main run loop by hand, which also
 * runs Dispatchers.Main, and drive the engine's WebKit callbacks with the fakes below.
 */

/** Runs the main run loop until [done] or [seconds] pass; true if [done]. */
internal fun pumpUntil(seconds: Double = 5.0, done: () -> Boolean): Boolean {
    val deadline = NSDate().timeIntervalSince1970 + seconds
    while (!done()) {
        if (NSDate().timeIntervalSince1970 > deadline) return false
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
    }
    return true
}
