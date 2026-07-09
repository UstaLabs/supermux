package dev.supermux.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.desktop.pairing.OnboardingScreen
import dev.supermux.desktop.pairing.PairingState
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.WorkspaceRoot

fun main() {
    val store = DesktopTokenStore()
    // Dev override, mirrors the mac app's SM_PAIR_TOKEN/SM_PAIR_BASE guard (SupermuxApp.swift
    // requires BOTH to be present and non-empty) — lets a dev/CI run seed a pairing without the
    // onboarding UI. Requiring both prevents a stray SM_PAIR_TOKEN from silently clobbering a
    // real user token, and a mismatched token/baseUrl pair from bypassing TOFU.
    val envToken = System.getenv("SM_PAIR_TOKEN")?.takeIf { it.isNotBlank() }
    val envBase = System.getenv("SM_PAIR_BASE")?.takeIf { it.isNotBlank() }
    if (envToken != null && envBase != null) {
        // A filesystem error here must not crash main() before the window ever opens —
        // log and fall through to normal onboarding instead.
        runCatching {
            store.save(envToken)
            store.saveBaseUrl(envBase)
        }.onSuccess {
            println("[Main] dev pairing seed applied from SM_PAIR_* env")
        }.onFailure { e ->
            println("[Main] dev pairing seed failed (falling through to onboarding): $e")
        }
    } else if (envToken != null || envBase != null) {
        println("[Main] ignoring partial SM_PAIR_* env — both SM_PAIR_TOKEN and SM_PAIR_BASE must be set")
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "supermux",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            // TODO(M4): drive from Settings/Appearance instead of a hardcoded default.
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                var paired by remember {
                    mutableStateOf(!store.load().isNullOrBlank() && !store.loadBaseUrl().isNullOrBlank())
                }
                if (!paired) {
                    val scope = rememberCoroutineScope()
                    val pairing = remember { PairingState(store, scope) }
                    DisposableEffect(Unit) { onDispose { pairing.close() } }
                    OnboardingScreen(pairing, onPaired = { paired = true })
                } else {
                    val scope = rememberCoroutineScope()
                    val app = remember { DesktopAppState(store.loadBaseUrl()!!, store.load()!!, scope) }
                    DisposableEffect(Unit) { onDispose { app.close() } }
                    WorkspaceRoot(app)
                }
            }
        }
    }
}
