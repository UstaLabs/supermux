// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.push

import dev.supermux.net.BrokerApi
import dev.supermux.ui.platform.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

// ── js() helpers ──────────────────────────────────────────────────────────────
//
// kotlinx-browser types neither `Notification` nor `PushManager`, so everything below crosses the
// boundary the way plans 2–3 established: a `js(...)` function per operation, promises resumed
// through a callback (never a `Promise` value, which wasm cannot await), and structured results
// handed back as a JSON STRING that kotlinx.serialization parses — no JsAny field walking.
//
// Every helper swallows its own failures and answers with a "nothing happened" value. Push is
// best-effort: this whole flow runs from a Compose effect, where a throw kills the composition.

/** `"granted" | "denied" | "default"`, or `""` when the browser has no Notification API at all. */
private fun notificationPermissionJs(): String =
    js("""(typeof Notification === "undefined" ? "" : String(Notification.permission))""")

/** True only when all three halves of Web Push exist (Notification, a SW container, PushManager). */
private fun pushSupportedJs(): Boolean =
    js("""(typeof Notification !== "undefined" && "serviceWorker" in navigator && "PushManager" in window)""")

/** `Notification.requestPermission()`. Resolves with the resulting permission, `""` if it threw. */
@Suppress("UNUSED_PARAMETER")
private fun requestPermissionJs(onDone: (String) -> Unit): Unit = js(
    """{
      try {
        // Safari <16 only has the callback form; the modern one returns a promise. Promise.resolve
        // normalises both — the callback form returns undefined, which resolves to undefined and
        // then reads Notification.permission below.
        Promise.resolve(Notification.requestPermission()).then(
          function (p) { onDone(String(p || Notification.permission || "")); },
          function () { onDone(""); }
        );
      } catch (e) { onDone(""); }
    }""",
)

/** `navigator.serviceWorker.ready`. Resolves with the registration, or null when there is none. */
@Suppress("UNUSED_PARAMETER")
private fun swReadyJs(onDone: (JsAny?) -> Unit): Unit = js(
    """{
      try {
        if (!("serviceWorker" in navigator)) { onDone(null); return; }
        navigator.serviceWorker.ready.then(function (r) { onDone(r || null); }, function () { onDone(null); });
      } catch (e) { onDone(null); }
    }""",
)

/**
 * `navigator.serviceWorker.getRegistration()` — the registration if one EXISTS, null otherwise.
 *
 * Deliberately not [swReadyJs]: `navigator.serviceWorker.ready` never resolves at all when no
 * worker is registered (it is a promise for "the active worker", not a probe), so every caller
 * that must not hang on a page without a service worker uses this instead. [register]'s subscribe
 * path still wants `ready` — it genuinely needs an ACTIVE worker to subscribe against.
 */
@Suppress("UNUSED_PARAMETER")
private fun swRegistrationNowJs(onDone: (JsAny?) -> Unit): Unit = js(
    """{
      try {
        if (!("serviceWorker" in navigator)) { onDone(null); return; }
        navigator.serviceWorker.getRegistration().then(function (r) { onDone(r || null); }, function () { onDone(null); });
      } catch (e) { onDone(null); }
    }""",
)

/** The existing subscription as `JSON.stringify(sub.toJSON())`, or `""` when there is none. */
@Suppress("UNUSED_PARAMETER")
private fun getSubscriptionJs(reg: JsAny, onDone: (String) -> Unit): Unit = js(
    """{
      try {
        reg.pushManager.getSubscription().then(
          function (s) { onDone(s ? JSON.stringify(s.toJSON()) : ""); },
          function () { onDone(""); }
        );
      } catch (e) { onDone(""); }
    }""",
)

/**
 * `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})` → the same JSON, or `""`.
 * [key] arrives as an `Int8Array` (Kotlin's only typed-array bridge) and is re-viewed as the
 * `Uint8Array` the Push API specifies over the SAME bytes — no copy, no sign reinterpretation.
 */
@Suppress("UNUSED_PARAMETER")
private fun subscribeJs(reg: JsAny, key: Int8Array, onDone: (String) -> Unit): Unit = js(
    """{
      try {
        var k = new Uint8Array(key.buffer, key.byteOffset, key.byteLength);
        reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: k }).then(
          function (s) { onDone(s ? JSON.stringify(s.toJSON()) : ""); },
          function (e) { console.warn("[push] subscribe failed:", e && e.message ? e.message : e); onDone(""); }
        );
      } catch (e) { console.warn("[push] subscribe threw:", e); onDone(""); }
    }""",
)

/** Drop the browser-side subscription. Always resolves. */
@Suppress("UNUSED_PARAMETER")
private fun unsubscribeJs(reg: JsAny, onDone: () -> Unit): Unit = js(
    """{
      try {
        reg.pushManager.getSubscription().then(
          function (s) { if (!s) { onDone(); return; } s.unsubscribe().then(function () { onDone(); }, function () { onDone(); }); },
          function () { onDone(); }
        );
      } catch (e) { onDone(); }
    }""",
)

/** Close every shown notification carrying [tag] — the chat the user just opened. */
@Suppress("UNUSED_PARAMETER")
private fun closeNotificationsJs(reg: JsAny, tag: String, onDone: () -> Unit): Unit = js(
    """{
      try {
        reg.getNotifications({ tag: tag }).then(
          function (ns) { for (var i = 0; i < ns.length; i++) { try { ns[i].close(); } catch (e) {} } onDone(); },
          function () { onDone(); }
        );
      } catch (e) { onDone(); }
    }""",
)

// ── the registrar ─────────────────────────────────────────────────────────────

/** `PushSubscription.toJSON()` — the only two fields the broker wants. */
@Serializable
private data class PushSubKeys(val p256dh: String = "", val auth: String = "")

@Serializable
private data class PushSubJson(val endpoint: String = "", val keys: PushSubKeys = PushSubKeys())

/**
 * The browser's [PushRegistrar]: W3C Web Push (VAPID) against the broker's `/push` routes.
 *
 * This is the Kotlin port of the Vue app's `useNotifications` state machine
 * (src/web-app/src/composables/useNotifications.ts), minus the `status` ref — the shared shell
 * asks for actions, not for a status, and the one place that needs a state (the enable banner)
 * reads [permission].
 *
 * **[api] is a function, not a value.** The registrar is built in `main()` before the fleet
 * exists, and the active host can change under it; resolving the `BrokerApi` per call is what lets
 * the registrar be constructed once and handed to [dev.supermux.web.WebPlatform]. A null answer
 * ("no host yet") is a quiet no-op, not an error.
 *
 * **Nothing here throws.** Every member is called from a Compose effect or a click handler; a
 * failure is logged and the flow stops. That includes the interesting failure mode in headless
 * Chrome and on a broker with no VAPID keys: `subscribe()` rejects, and the app simply stays
 * unsubscribed instead of tearing down the composition.
 *
 * The one rule that is NOT ours to soften: a push must never be suppressed client-side (WebKit
 * revokes the subscription when a delivered push shows no notification). Suppression is the
 * broker's job, through the `viewing` frame — see `sw.js`, which always shows something.
 */
class WebPushRegistrar(
    private val api: () -> BrokerApi?,
    private val scope: CoroutineScope,
) : PushRegistrar {

    private val json = Json { ignoreUnknownKeys = true }

    private val _permission = MutableStateFlow(notificationPermissionJs())

    /** `"granted" | "denied" | "default"`, or `""` where the browser has no Notification API. */
    val permission: StateFlow<String> = _permission.asStateFlow()

    /** True when this browser has all of Notification + service workers + PushManager. */
    val supported: Boolean get() = pushSupportedJs()

    /** Android's notification CHANNEL has no web counterpart — `sw.js` decides how a push renders. */
    override fun ensureChannel() = Unit

    override fun requestPermission() { scope.launch { requestPermissionNow() } }

    override fun registerIfPaired() { scope.launch { register() } }

    override fun cancelForSession(sessionId: String) {
        scope.launch {
            val reg = swRegistrationNow() ?: return@launch
            // The tag `sw.js` writes: one notification per chat, so closing the tag closes the lot.
            suspendCancellableCoroutine { cont ->
                closeNotificationsJs(reg, "cmux:$sessionId") { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    /**
     * Ask for the notification permission and remember the answer. Suspending (unlike the seam's
     * fire-and-forget [requestPermission]) because the banner has to register IMMEDIATELY after a
     * grant, and two `scope.launch`es would race.
     */
    suspend fun requestPermissionNow(): String {
        if (!supported) return ""
        val result = suspendCancellableCoroutine { cont ->
            requestPermissionJs { p -> if (cont.isActive) cont.resume(p) }
        }
        val settled = result.ifBlank { notificationPermissionJs() }
        _permission.value = settled
        return settled
    }

    /**
     * Subscribe, or reconcile an existing subscription with the broker. Idempotent — safe on every
     * launch, which is exactly how `Main.kt` calls it.
     *
     * The reconcile branch is the Vue `probe()`: a subscription can outlive the broker's record of
     * it (a restored profile, a re-installed broker), and re-POSTing is a cheap upsert. A 401/404
     * means the broker will not accept this device at all, so the local subscription is dropped and
     * the banner comes back; any other failure is transient and the subscription is kept.
     *
     * @return whether this browser now holds a subscription the BROKER knows about. False is
     *   ordinary on a launch where the permission was never granted (the banner's job), but after
     *   an explicit grant it means the enable actually failed — which is the one place a user is
     *   waiting on an answer, so [WebPushBanner] turns it into a notice instead of vanishing.
     */
    suspend fun register(): Boolean {
        if (!supported) return false
        _permission.value = notificationPermissionJs()
        // Not granted → never PROMPT. The banner asks; a silent permission prompt on every load is
        // exactly the pattern browsers punish. But "not granted" is not the same as "nothing to
        // do": the user can revoke the permission in site settings at any time, and the broker
        // would go on pushing to an endpoint that can no longer show anything — which is precisely
        // what makes WebKit revoke the subscription outright. So a leftover subscription is torn
        // down on both sides first. (Vue's `probe()` reconciled for any non-denied permission; this
        // reconciles for any non-granted one, which is the same idea seen from the other side.)
        if (_permission.value != "granted") {
            disableIfSubscribed()
            return false
        }
        val broker = api() ?: return false
        val reg = swReady() ?: return false

        val existing = parse(getSubscription(reg))
        if (existing != null) {
            val ok = quietly { broker.pushSubscribe(existing.endpoint, existing.keys.p256dh, existing.keys.auth) }
            // Three outcomes, and the difference matters:
            //   true  — reconciled, done.
            //   false — the broker ANSWERED and refused. `pushSubscribe` collapses every non-2xx
            //           into false, so this cannot tell "not authorised" from "no push store";
            //           either way the broker will not push here, so the subscription is dropped
            //           and the next load re-subscribes from scratch. That is Vue `probe()`'s
            //           401/404 branch, widened to every refusal the Boolean cannot separate.
            //   null  — the call never completed (offline, tab suspended). KEEP the subscription:
            //           it is still valid and the next launch reconciles it. Vue's "network blip —
            //           assume subscribed", and the whole reason `pushSubscribe` is tri-state.
            if (ok == false) unsubscribeLocally(reg)
            // `null` kept the subscription but the broker has no confirmed record of it, so only
            // an accepted re-POST counts as "registered".
            return ok == true
        }

        val keyB64 = quietly { broker.pushVapidPublicKey() }
        if (keyB64.isNullOrBlank()) {
            // The broker has no VAPID keys. Not an error — this deployment simply has no push.
            println("[push] broker has no VAPID key; staying unsubscribed")
            return false
        }
        val bytes = vapidKeyBytes(keyB64)
        if (bytes.isEmpty()) {
            println("[push] VAPID key did not decode")
            return false
        }
        val fresh = parse(subscribe(reg, bytes))
        if (fresh == null) {
            println("[push] pushManager.subscribe() produced no subscription")
            return false
        }
        return when (quietly { broker.pushSubscribe(fresh.endpoint, fresh.keys.p256dh, fresh.keys.auth) }) {
            true -> true
            // Answered and refused: the broker has no record, so a local subscription is dead
            // weight — and keeping it would make the next launch take the reconcile branch and
            // never retry a fresh subscribe.
            false -> {
                println("[push] broker rejected the subscription; dropping it locally")
                unsubscribeLocally(reg)
                false
            }
            // Never completed. KEEP it: the subscription is valid and the next launch reconciles.
            null -> {
                println("[push] could not reach the broker to register the subscription")
                false
            }
        }
    }

    /** Tear down a subscription on both sides. Used when the permission is gone — see [register]. */
    private suspend fun disableIfSubscribed() {
        val reg = swRegistrationNow() ?: return
        if (parse(getSubscription(reg)) == null) return
        println("[push] notification permission is no longer granted; dropping the subscription")
        unsubscribeLocally(reg)
        api()?.let { quietly { it.pushUnsubscribe() } }
    }

    /** Turn push off: drop the browser subscription AND the broker's record of it. */
    suspend fun disable() {
        val reg = swRegistrationNow()
        if (reg != null) unsubscribeLocally(reg)
        api()?.let { quietly { it.pushUnsubscribe() } }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private suspend fun swReady(): JsAny? = suspendCancellableCoroutine { cont ->
        swReadyJs { r -> if (cont.isActive) cont.resume(r) }
    }

    private suspend fun swRegistrationNow(): JsAny? = suspendCancellableCoroutine { cont ->
        swRegistrationNowJs { r -> if (cont.isActive) cont.resume(r) }
    }

    private suspend fun getSubscription(reg: JsAny): String = suspendCancellableCoroutine { cont ->
        getSubscriptionJs(reg) { s -> if (cont.isActive) cont.resume(s) }
    }

    private suspend fun subscribe(reg: JsAny, key: ByteArray): String = suspendCancellableCoroutine { cont ->
        subscribeJs(reg, key.toInt8Array()) { s -> if (cont.isActive) cont.resume(s) }
    }

    private suspend fun unsubscribeLocally(reg: JsAny) {
        suspendCancellableCoroutine { cont -> unsubscribeJs(reg) { if (cont.isActive) cont.resume(Unit) } }
    }

    private fun parse(raw: String): PushSubJson? {
        if (raw.isBlank()) return null
        val sub = runCatching { json.decodeFromString<PushSubJson>(raw) }.getOrNull() ?: return null
        return sub.takeIf { it.endpoint.isNotBlank() && it.keys.p256dh.isNotBlank() && it.keys.auth.isNotBlank() }
    }

    /**
     * Run a broker call whose only interesting failure is "it did not happen".
     *
     * The three push methods answer a failed call with `null`/`false` and rethrow
     * `CancellationException` untouched, so — unlike most of `BrokerApi`, which reports transport
     * failures AS cancellation — the only cancellation reaching here is a REAL one: the page scope
     * going away. That has to propagate, or [register] would carry on issuing calls on a dead
     * scope. `isActive` is what tells the two apart.
     */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        if (!coroutineContext.isActive) throw c
        println("[push] broker call reported cancellation")
        null
    } catch (e: Throwable) {
        println("[push] broker call failed: ${e.message?.take(160)}")
        null
    }
}
