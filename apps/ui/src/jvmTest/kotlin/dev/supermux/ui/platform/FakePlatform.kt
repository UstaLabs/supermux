package dev.supermux.ui.platform

import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics

/**
 * A recording [Platform] — the pattern every screen test uses to assert a platform call.
 *
 * Lives in its own file (rather than private to one test) because every shared screen that reaches
 * for the platform needs one; `caps` and [qrResult] are constructor knobs so a test can say "this
 * machine has a camera and the user scanned X" in one line.
 */
internal class FakePlatform(
    override val caps: Caps = NO_CAPS,
    override val haptics: Haptics = NoHaptics,
    /** What [scanQr] hands back; null = the user cancelled (or there is no camera). */
    var qrResult: String? = null,
) : Platform {
    val openedUrls = mutableListOf<String>()
    val copied = mutableListOf<String>()
    var pickedKind: PickKind? = null
    var pickedRequester: String? = null
    var scans = 0

    override fun openUrl(url: String) { openedUrls.add(url) }
    override fun copyToClipboard(text: String) { copied.add(text) }
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> {
        pickedKind = kind
        pickedRequester = requester
        return listOf(PickedFile("a.txt", "text/plain", ByteArrayChunkSource(byteArrayOf(1, 2))))
    }
    override suspend fun scanQr(): String? {
        scans++
        return qrResult
    }
}

/** A machine that can do nothing — the default, so a test opts INTO each capability it exercises. */
internal val NO_CAPS = Caps(
    push = false,
    camera = false,
    tray = false,
    externalDisplay = false,
    hardwareVideoDecode = false,
    localBroker = false,
    multiWindow = false,
    fileSystem = false,
)
