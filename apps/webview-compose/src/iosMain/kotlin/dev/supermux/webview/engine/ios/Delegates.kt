package dev.supermux.webview.engine.ios

import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

/*
 * WebKit's delegates, each forwarding to its engine. WebKit holds the navigation and UI delegates weakly and the message
 * handler strongly (through the configuration), so they point at the engine, never the other way round only: the
 * engine clears [engine] at release, which ends the cycle. With no engine, every question gets its safe answer.
 */

internal class NavigationDelegate : NSObject(), WKNavigationDelegateProtocol {
    var engine: IosWebEngine? = null

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        preferences: WKWebpagePreferences,
        decisionHandler: (WKNavigationActionPolicy, WKWebpagePreferences?) -> Unit,
    ) {
        val engine = engine
        if (engine == null) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel, preferences)
            return
        }
        engine.decideAction(decidePolicyForNavigationAction, preferences, decisionHandler)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
        val engine = engine
        if (engine == null) {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            return
        }
        engine.decideResponse(decidePolicyForNavigationResponse, decisionHandler)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        engine?.onStarted(didStartProvisionalNavigation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        engine?.onCommitted(didCommitNavigation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        engine?.onFinished(didFinishNavigation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        engine?.onFailed(didFailProvisionalNavigation, withError)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        engine?.onFailed(didFailNavigation, withError)
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        engine?.onRendererGone()
    }
}

internal class UiDelegate : NSObject(), WKUIDelegateProtocol {
    var engine: IosWebEngine? = null

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        engine?.onNewWindow(forNavigationAction)
        return null
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptAlertPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: () -> Unit,
    ) {
        val engine = engine ?: return completionHandler()
        engine.onAlert(runJavaScriptAlertPanelWithMessage, initiatedByFrame, completionHandler)
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (Boolean) -> Unit,
    ) {
        val engine = engine ?: return completionHandler(false)
        engine.onConfirm(runJavaScriptConfirmPanelWithMessage, initiatedByFrame, completionHandler)
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt: String,
        defaultText: String?,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (String?) -> Unit,
    ) {
        val engine = engine ?: return completionHandler(null)
        engine.onPrompt(runJavaScriptTextInputPanelWithPrompt, defaultText, initiatedByFrame, completionHandler)
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val engine = engine ?: return decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
        engine.onMediaCapture(requestMediaCapturePermissionForOrigin, type, decisionHandler)
    }

    override fun webView(
        webView: WKWebView,
        requestDeviceOrientationAndMotionPermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val engine = engine ?: return decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
        engine.onMotion(requestDeviceOrientationAndMotionPermissionForOrigin, decisionHandler)
    }
}

internal class RelayHandler : NSObject(), WKScriptMessageHandlerProtocol {
    var engine: IosWebEngine? = null

    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        engine?.onRelayMessage(didReceiveScriptMessage)
    }
}
