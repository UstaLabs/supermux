package dev.supermux.push

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Android actual: byte-for-byte identical to the JVM actual. Android is a separate
// KMP source set (can't see jvmMain), so it needs its own actual; it ships the same
// java.security / javax.crypto APIs, so the algorithm is unchanged. The JVM copy in
// :shared:jvmTest is the source of truth that proves broker interop on this host.
//
// java.util.Base64 requires Android API 26+ (the shared module's minSdk); if minSdk
// ever drops below 26, swap to android.util.Base64 (URL_SAFE | NO_PADDING | NO_WRAP).
actual fun openSealedPush(blob: String, privateKeyPkcs8B64: String): String {
    // 1. Split + base64url-decode the 4 wire parts. (Blob parts are base64url;
    //    the PKCS#8 private key is standard base64.)
    val parts = blob.split(".")
    require(parts.size == 4) { "sealed push blob must have 4 dot-joined parts, got ${parts.size}" }
    val urlDec = Base64.getUrlDecoder()
    val ephPubRaw = urlDec.decode(parts[0])
    val salt = urlDec.decode(parts[1])
    val iv = urlDec.decode(parts[2])
    val ct = urlDec.decode(parts[3])
    require(ephPubRaw.size == 65 && ephPubRaw[0].toInt() == 0x04) {
        "ephPub must be a 65-byte uncompressed P-256 point (0x04 prefix)"
    }

    // 2. Import the recipient EC private key (PKCS#8, standard base64).
    val privKeyDer = Base64.getDecoder().decode(privateKeyPkcs8B64)
    val ecKeyFactory = KeyFactory.getInstance("EC")
    val privateKey = ecKeyFactory.generatePrivate(PKCS8EncodedKeySpec(privKeyDer))

    // 3. Import the ephemeral P-256 public key from its raw uncompressed point.
    //    X = bytes[1..33], Y = bytes[33..65] (big-endian, unsigned).
    val x = java.math.BigInteger(1, ephPubRaw.copyOfRange(1, 33))
    val y = java.math.BigInteger(1, ephPubRaw.copyOfRange(33, 65))
    val secp256r1: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    val ephPub = ecKeyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), secp256r1))

    // 4. ECDH -> 32-byte shared secret Z (the X coordinate of the shared point).
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(privateKey)
    ka.doPhase(ephPub, true)
    val z = ka.generateSecret()

    // 5. HKDF-SHA256 (no built-in HKDF). info = "supermux-push" (ASCII).
    //    extract:  prk = HMAC-SHA256(key=salt, msg=Z)
    //    expand:   okm = HMAC-SHA256(key=prk, msg=info || 0x01); take first 32 bytes.
    val info = "supermux-push".toByteArray(Charsets.US_ASCII)
    val prk = hmacSha256(salt, z)
    val okm = hmacSha256(prk, info + byteArrayOf(0x01))
    val aesKey = okm.copyOfRange(0, 32)

    // 6. AES-256-GCM decrypt. JCA expects ciphertext+tag concatenated, which is
    //    exactly our `ct` part (16-byte tag trailing). 128-bit tag length.
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
    val plaintext = cipher.doFinal(ct)
    return plaintext.toString(Charsets.UTF_8)
}

private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}
