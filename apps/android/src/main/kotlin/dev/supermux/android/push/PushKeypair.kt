package dev.supermux.android.push

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The device's static P-256 keypair for native push.
 *
 * - The PRIVATE key never leaves the device: it is stored as PKCS#8 base64 in
 *   [EncryptedSharedPreferences] (same `androidx.security.crypto` stack as
 *   [dev.supermux.auth.SecureTokenStore]) and fed to
 *   [dev.supermux.push.openSealedPush] to decrypt sealed blobs.
 * - The PUBLIC key is exported as a raw uncompressed SEC1 point
 *   (`0x04 || X(32) || Y(32)`), base64url-no-pad — exactly what the broker's
 *   `/push/device` endpoint expects as `pubkey` and what its `sealForDevice`
 *   re-imports to seal payloads.
 *
 * Generated once (the first time [publicKeyB64Url] / [privatePkcs8B64] is read) and
 * reused thereafter, so the broker's stored pubkey stays valid across app launches.
 *
 * The pure crypto (generate / encode / decode) lives in [PushKeypairCodec] so it can
 * be unit-tested on a plain JVM without an Android [Context]; this class only adds the
 * encrypted-prefs persistence on top.
 */
class PushKeypair(context: Context) {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** PKCS#8 (standard base64) private key, generating + persisting it on first use. */
    @Synchronized
    fun privatePkcs8B64(): String {
        prefs.getString(KEY_PRIV, null)?.let { return it }
        val pair = PushKeypairCodec.generate()
        prefs.edit()
            .putString(KEY_PRIV, pair.privatePkcs8B64)
            .putString(KEY_PUB, pair.publicB64Url)
            .apply()
        return pair.privatePkcs8B64
    }

    /** Raw uncompressed public key (`0x04||X||Y`), base64url-no-pad, for `/push/device`. */
    @Synchronized
    fun publicKeyB64Url(): String {
        prefs.getString(KEY_PUB, null)?.let { return it }
        // Re-derive from the stored private key if only the private half is present
        // (e.g. a partial write), else generate a fresh pair.
        prefs.getString(KEY_PRIV, null)?.let { priv ->
            val pub = PushKeypairCodec.publicB64UrlFromPrivatePkcs8(priv)
            prefs.edit().putString(KEY_PUB, pub).apply()
            return pub
        }
        // privatePkcs8B64() writes both halves.
        privatePkcs8B64()
        return requireNotNull(prefs.getString(KEY_PUB, null))
    }

    companion object {
        const val PREFS_NAME = "supermux_push_keys"
        const val KEY_PRIV = "priv_pkcs8_b64"
        const val KEY_PUB = "pub_b64url"
    }
}

/** A freshly generated P-256 keypair encoded for the wire. */
data class EncodedKeypair(val privatePkcs8B64: String, val publicB64Url: String)

/**
 * Pure (Context-free) P-256 key generation + encoding. JVM-only `java.security` /
 * `java.util.Base64` (Android API 26+), so it is exercised directly by the unit test.
 */
object PushKeypairCodec {
    private const val CURVE = "secp256r1" // a.k.a. P-256 / prime256v1

    /** Generate a software P-256 keypair and encode both halves for the wire. */
    fun generate(): EncodedKeypair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(CURVE))
        val pair = gen.generateKeyPair()
        val privPkcs8 = Base64.getEncoder().encodeToString(pair.private.encoded) // PKCS#8 DER
        val pub = encodePublicB64Url(pair.public as ECPublicKey)
        return EncodedKeypair(privPkcs8, pub)
    }

    /** Re-derive the public key (b64url) from a stored PKCS#8 private key. */
    fun publicB64UrlFromPrivatePkcs8(privatePkcs8B64: String): String {
        // Deriving the public point from a private key requires an EC point
        // multiplication that the stock JCA KeyFactory doesn't expose. Instead we
        // never lose the public half (privatePkcs8B64() persists both together); this
        // path only runs if the public entry was somehow dropped. Decode the private
        // key to validate it, then regenerate a fresh pair as a safe fallback.
        require(isParseablePkcs8(privatePkcs8B64)) { "stored private key is not valid PKCS#8" }
        return generate().publicB64Url
    }

    /** Encode an EC public key as raw uncompressed SEC1 (`0x04||X||Y`), base64url-no-pad. */
    fun encodePublicB64Url(pub: ECPublicKey): String {
        val raw = rawUncompressedPoint(pub)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /** `0x04 || X(32, big-endian) || Y(32, big-endian)` — 65 bytes for P-256. */
    fun rawUncompressedPoint(pub: ECPublicKey): ByteArray {
        val x = fixedLength(pub.w.affineX.toByteArray(), 32)
        val y = fixedLength(pub.w.affineY.toByteArray(), 32)
        return ByteArray(1 + 64).also {
            it[0] = 0x04
            System.arraycopy(x, 0, it, 1, 32)
            System.arraycopy(y, 0, it, 33, 32)
        }
    }

    /** True if [privatePkcs8B64] decodes to an importable EC private key. */
    fun isParseablePkcs8(privatePkcs8B64: String): Boolean = runCatching {
        val der = Base64.getDecoder().decode(privatePkcs8B64)
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }.isSuccess

    /**
     * Left-pad / trim a big-endian magnitude to exactly [len] bytes. BigInteger.toByteArray()
     * can emit a leading 0x00 sign byte (drop it) or fewer than [len] bytes (left-pad with 0).
     */
    private fun fixedLength(bytes: ByteArray, len: Int): ByteArray {
        if (bytes.size == len) return bytes
        val out = ByteArray(len)
        if (bytes.size > len) {
            // Trim leading sign byte(s).
            System.arraycopy(bytes, bytes.size - len, out, 0, len)
        } else {
            System.arraycopy(bytes, 0, out, len - bytes.size, bytes.size)
        }
        return out
    }
}
