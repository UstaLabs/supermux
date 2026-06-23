package dev.supermux.push

// Apple actual: a placeholder slot so the Apple build has the `actual` for `openSealedPush`.
// Implemented with CryptoKit (P256.KeyAgreement + HKDF<SHA256> with info="supermux-push"
// + AES.GCM) in the iOS client task on the Mac. The :shared jvmTest proves the algorithm
// and wire format against a real broker blob; the CryptoKit impl must match it byte-for-byte.
//
// Why appleMain (not iosMain): the module declares four Apple targets — iosArm64,
// iosSimulatorArm64, watchosArm64, watchosSimulatorArm64 — that share Darwin code via the
// default hierarchy's intermediate `appleMain` source set (same convention as
// SecureTokenStore.apple.kt / Inflate.apple.kt here). A single actual in `appleMain`
// satisfies the expect for ALL FOUR; an actual in `iosMain` alone would leave the two
// watchOS targets' `expect` unimplemented and break the watchOS framework build on the Mac.
//
// NOTE: Apple targets are DISABLED on this Linux host (kotlin.native.ignoreDisabledTargets),
// so this file is not compiled here — it exists so the later Mac build has the slot.
actual fun openSealedPush(blob: String, privateKeyPkcs8B64: String): String =
    TODO("appleMain — implemented with CryptoKit in the iOS client task on the Mac")
