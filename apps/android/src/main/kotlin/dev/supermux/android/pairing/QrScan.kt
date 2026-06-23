package dev.supermux.android.pairing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * QR pairing scan via `zxing-android-embedded`'s self-contained capture activity.
 *
 * The library ships its own `CAMERA`-permission capture screen + an
 * [ActivityResultContract][androidx.activity.result.contract.ActivityResultContract],
 * so there is zero CameraX/ImageAnalysis boilerplate and no Google-Play-Services
 * runtime dependency (works on de-Googled / offline-first devices — exactly our
 * self-hosting audience). The decoded string is the pairing URL we feed straight
 * into [dev.supermux.net.PairUrl.parse].
 */

/** ZXing scan options: QR-only, no beep, free orientation. */
private fun qrScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setBeepEnabled(false)
    .setOrientationLocked(false)
    .setPrompt("Scan the broker pairing QR")

/**
 * A `CAMERA`-gated QR scan launcher. Returns a no-arg `launch()` lambda: it requests
 * the camera permission (same pattern the repo already uses for runtime perms) and,
 * once granted, opens the ZXing capture activity. [onResult] gets the decoded string,
 * or null when the user cancels / denies the permission.
 */
@Composable
fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onResult(result.contents) // null when the user backs out of the scanner
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanLauncher.launch(qrScanOptions()) else onResult(null)
    }
    return { permissionLauncher.launch(Manifest.permission.CAMERA) }
}
