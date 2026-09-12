// The wizard's "Connect Your Phone" step — the port of Vue's `SetupStepPhone.vue`.
//
// Same contract as the web app it replaces: on entry MINT a one-time device named "phone", show
// its pairing URL as a QR plus copyable text, poll `GET /devices` once a second until the broker
// reports a `last_seen_at` for that device, and revoke the code again if the user leaves without
// using it. A pairing link is a bearer credential for the whole host, so an abandoned one must not
// outlive the step that showed it.
//
// Deliberately NOT `DevicesSettingsScreen`'s `AddDeviceDialog`: that one is name-FIRST (type a
// name, then get a link) and lives inside a settings list. Here the name is fixed, the mint is
// automatic, and the QR is the hero — same visual vocabulary (white plate, mono URL), different
// flow.
package dev.supermux.ui.intro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.AddDeviceResponse
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.qrBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The device name the wizard always mints — Vue's `api.addDevice("phone")`. */
private const val PHONE_DEVICE_NAME = "phone"

/** Vue's `setInterval(checkPairing, 1_000)`. */
private const val PAIR_POLL_MS = 1_000L

/** How long the copy button stays in its "Copied" state. */
private const val COPIED_MS = 1_500L

/** QR side — bigger than the settings dialog's: this one is meant to be scanned across a desk. */
private val PhoneQrSize = 240.dp

/** The in-flight mint, held across recompositions. Not state: nothing renders from it. */
private class MintJobHolder {
    var job: Job? = null
}

/**
 * Revoke [name] only if the broker still lists it as never-seen.
 *
 * Best effort and deliberately confirm-first: a transient `GET /devices` failure returns `null`
 * here and we do NOT revoke — better to leave a stale pairing code (it is one-time and the user
 * can revoke it from Settings) than to delete the device the phone just paired with.
 */
private suspend fun revokeIfUnused(devices: DevicesSettingsActions, name: String) {
    val listed = devices.devicesLoad() ?: return
    val device = listed.firstOrNull { it.name == name } ?: return
    if (device.last_seen_at == null) devices.deviceRevoke(name)
}

/**
 * Mint a one-time pairing code for a phone, show it, and watch for the phone to use it.
 *
 * @param scope MUST be the app scope. The leave-the-step revoke is launched from `onDispose`, so
 *   it has to survive both this composable and whatever removed it: a `rememberCoroutineScope()`
 *   taken here is already cancelled by then, and even the enclosing wizard's own scope dies the
 *   instant the host swaps the wizard out on `onboarded=true` — mid-revoke, which is two requests
 *   (a list, then a DELETE). An un-revoked pairing link is a host-wide bearer credential, so there
 *   is deliberately NO default: a `rememberCoroutineScope()` fallback would be a silent footgun
 *   for the next caller. [SetupWizardScreen] takes the same scope as a required parameter and
 *   passes it straight through.
 */
@Composable
fun SetupPhoneStep(
    devices: DevicesSettingsActions,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current

    // Held as the MutableState objects, not just `by` delegates: `onDispose` below must read the
    // CURRENT pairing, and `rememberUpdatedState` only refreshes on recomposition — a refresh
    // immediately followed by leaving the step would hand the old (already revoked) name to the
    // cleanup and leak the newly minted one.
    val pairingState = remember { mutableStateOf<AddDeviceResponse?>(null) }
    val pairedState = remember { mutableStateOf(false) }
    var pairing by pairingState
    var paired by pairedState
    /** The in-flight mint (initial, refresh or retry) — `onDispose` waits for it, see below. */
    val minting = remember { MintJobHolder() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(0) }

    suspend fun mint(refresh: Boolean) {
        busy = true
        if (refresh) {
            pairing?.name?.takeIf { !paired }?.let { revokeIfUnused(devices, it) }
            pairing = null
            paired = false
        }
        loading = true
        error = null
        val minted = devices.deviceAdd(PHONE_DEVICE_NAME)
        if (minted == null) {
            error = "Couldn't create a phone pairing code."
        } else {
            pairing = minted
            paired = false
        }
        loading = false
        busy = false
    }

    // The first mint runs on [scope], not on this effect's: `POST /devices` may reach the broker
    // just as the step goes away, and a mint cancelled with the composable would leave a live
    // pairing link nobody knows about. `LaunchedEffect(Unit)` still triggers it exactly once.
    LaunchedEffect(Unit) {
        if (minting.job == null) minting.job = scope.launch { mint(refresh = false) }
    }

    // Poll for the phone actually using the link. A failed load is skipped, never surfaced: a
    // transient blip must not replace a perfectly usable QR with an error.
    val watched = pairing?.name
    LaunchedEffect(watched) {
        if (watched == null) return@LaunchedEffect
        while (!paired) {
            // Check FIRST, then wait: a phone that scanned the code before this effect restarted
            // should flip the step without a dead second on screen.
            val listed = devices.devicesLoad()
            if (listed?.firstOrNull { it.name == watched }?.last_seen_at != null) {
                paired = true
                break
            }
            delay(PAIR_POLL_MS)
        }
    }

    // Leaving the step with an unused code revokes it (Vue's `onBeforeUnmount`). Keyed on `Unit`
    // so a "Refresh code" — which does its own revoke — does not also trip this one, and reading
    // the state objects directly so whatever is on screen AT dispose is what gets cleaned up.
    //
    // JOIN the in-flight mint rather than cancelling it: cancelling stops the state write, not the
    // device the broker may already have created, and that one would never be revoked. Waiting
    // costs nothing (the mint is one request) and leaves `pairingState` holding whatever was
    // actually minted. `revokeIfUnused` is confirm-first, so a code the phone grabbed in the
    // meantime survives. The whole thing runs on [scope], which outlives this composable —
    // launched on a scope taken here it would be cancelled before it ever dispatched.
    DisposableEffect(Unit) {
        onDispose {
            val inFlight = minting.job
            scope.launch {
                inFlight?.join()
                val name = pairingState.value?.name
                if (name != null && !pairedState.value) revokeIfUnused(devices, name)
            }
        }
    }

    if (copied > 0) {
        LaunchedEffect(copied) {
            delay(COPIED_MS)
            copied = 0
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("setup_phone_step"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = IntroPageMaxWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl, vertical = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            if (paired) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    "Your phone is connected",
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Supermux is paired and ready to use on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.sm))
                OutlinedButton(
                    onClick = { minting.job = scope.launch { mint(refresh = true) } },
                    enabled = !busy,
                    modifier = Modifier.testTag("setup_phone_another"),
                ) { Text("Connect another phone") }
            } else {
                Icon(
                    Icons.Filled.PhoneIphone,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    "Connect from your phone",
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Open Supermux on your iPhone, iPad, or Android device and scan this code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                val url = pairing?.url
                when {
                    loading -> {
                        Spacer(Modifier.height(Space.xl))
                        CircularProgressIndicator(
                            color = cs.primary,
                            modifier = Modifier.testTag("setup_phone_loading"),
                        )
                        Text(
                            "Creating a secure pairing code…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    url != null -> {
                        // `qrBitmap` throws only on input past the version-40 capacity; a pairing URL
                        // never is, but a broker with an absurd public URL would take the whole step
                        // down rather than just losing the QR.
                        val qr = remember(url) {
                            runCatching { qrBitmap(url, sizePx = 480) }.getOrNull()
                        }
                        Spacer(Modifier.height(Space.sm))
                        if (qr == null) {
                            Text(
                                "Couldn't render the QR — copy the link below and open it on " +
                                    "your phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("setup_phone_qr_fallback"),
                            )
                        } else {
                            Image(
                                bitmap = qr,
                                contentDescription = "Phone pairing QR code",
                                modifier = Modifier
                                    .size(PhoneQrSize)
                                    .clip(RoundedCornerShape(Radii.lg))
                                    .background(Color.White)
                                    .padding(Space.md)
                                    .testTag("setup_phone_qr"),
                            )
                        }
                        Text(
                            url,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = MonoFontFamily,
                            color = cs.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("setup_phone_url"),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    platform.copyToClipboard(url)
                                    copied = copied + 1
                                },
                                modifier = Modifier.testTag("setup_phone_copy"),
                            ) {
                                Icon(
                                    if (copied > 0) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(IconSize.md),
                                )
                                Spacer(Modifier.size(Space.sm))
                                Text(if (copied > 0) "Copied" else "Copy pairing link")
                            }
                            TextButton(
                                onClick = { minting.job = scope.launch { mint(refresh = true) } },
                                enabled = !busy,
                                modifier = Modifier.testTag("setup_phone_refresh"),
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(IconSize.md),
                                )
                                Spacer(Modifier.size(Space.sm))
                                Text("Refresh code")
                            }
                        }
                        Text(
                            "The pairing link grants access to this host. Keep it private and only " +
                                "scan it with your own device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("setup_phone_error"),
                    )
                    if (url == null) {
                        OutlinedButton(
                            onClick = { minting.job = scope.launch { mint(refresh = false) } },
                            enabled = !busy,
                            modifier = Modifier.testTag("setup_phone_retry"),
                        ) { Text("Try again") }
                    }
                }
            }
        }
    }
}
