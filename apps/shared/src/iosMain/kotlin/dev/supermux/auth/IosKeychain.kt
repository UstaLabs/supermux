package dev.supermux.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateCopy
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The iOS Keychain, as the four operations this app actually performs on it.
 *
 * It is a byte-for-byte reimplementation of what the SwiftUI app's `KeychainStore` and
 * `KeychainHostPersistence` do (cluster H2), and that is the whole point: an upgrade to the Compose
 * shell must find the items the Swift build wrote, or every paired user is silently logged out.
 * The parts that are load-bearing for that, and must not be "improved":
 *
 *  - `kSecClassGenericPassword` keyed by (`kSecAttrService`, `kSecAttrAccount`) — no access group.
 *    Adding one would move the items to a different keychain partition and read nothing.
 *  - `kSecAttrAccessibleAfterFirstUnlock` on write. The NSE and a background push both run while
 *    the device is locked, so a stricter class would break push after a reboot.
 *  - Write is `SecItemDelete` then `SecItemAdd`, never `SecItemUpdate`. An update on a missing item
 *    fails, and this way one code path serves create and replace.
 *  - Values are the RAW UTF-8 of the token — not a plist, not base64, not JSON.
 *
 * Everything here is synchronous. The Keychain is a local, fast, memory-mapped store and the app
 * reads it once at launch to decide whether it is paired at all; an asynchronous read would mean
 * painting a frame of the un-paired UI to a user who is paired.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosKeychain {

    /** The one token per host record (spec §3.2), matching Swift's `KeychainHostPersistence`. */
    const val HOSTS_SERVICE = "dev.supermux.hosts"

    /** The legacy single-host token, matching Swift's `KeychainStore`. */
    const val LEGACY_SERVICE = "dev.supermux.app"
    const val LEGACY_ACCOUNT = "device_token"

    /** The UTF-8 value stored for (service, account), or null when there is no such item. */
    fun get(service: String, account: String): String? = query { q ->
        q.put(kSecClass, kSecClassGenericPassword)
        q.putString(kSecAttrService, service)
        q.putString(kSecAttrAccount, account)
        q.put(kSecReturnData, kCFBooleanTrue)
        q.put(kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val out = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(q.dict, out.ptr) != errSecSuccess) return@query null
            // CFBridgingRelease consumes the +1 SecItemCopyMatching handed us — the value is
            // owned by us and would leak on every read otherwise.
            val data = CFBridgingRelease(out.value) as? NSData ?: return@query null
            data.toByteArray().decodeToString()
        }
    }

    /** Replace (service, account) with [value]. Delete-then-add, exactly as Swift does. */
    fun put(service: String, account: String, value: String) {
        remove(service, account)
        query { q ->
            q.put(kSecClass, kSecClassGenericPassword)
            q.putString(kSecAttrService, service)
            q.putString(kSecAttrAccount, account)
            q.putBytes(kSecValueData, value.encodeToByteArray())
            q.put(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            SecItemAdd(q.dict, null)
        }
    }

    /** Remove (service, account). A missing item is not an error. */
    fun remove(service: String, account: String) {
        query { q ->
            q.put(kSecClass, kSecClassGenericPassword)
            q.putString(kSecAttrService, service)
            q.putString(kSecAttrAccount, account)
            SecItemDelete(q.dict)
        }
    }

    /**
     * Every account that currently has an item under [service] — how a save prunes the tokens of
     * hosts the user has forgotten. `kSecMatchLimitAll` + `kSecReturnAttributes` (and deliberately
     * NOT `kSecReturnData`: enumerating does not need the secrets, and asking for them would be a
     * larger, slower read of material we immediately discard).
     */
    fun accounts(service: String): Set<String> = query { q ->
        q.put(kSecClass, kSecClassGenericPassword)
        q.putString(kSecAttrService, service)
        q.put(kSecMatchLimit, kSecMatchLimitAll)
        q.put(kSecReturnAttributes, kCFBooleanTrue)
        memScoped {
            val out = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(q.dict, out.ptr) != errSecSuccess) return@query emptySet()
            @Suppress("UNCHECKED_CAST")
            val items = CFBridgingRelease(out.value) as? List<Map<Any?, *>> ?: return@query emptySet()
            items.mapNotNull { it[ACCOUNT_ATTRIBUTE] as? String }.toSet()
        }
    }

    /**
     * `kSecAttrAccount`'s key as the plain String it becomes once a `SecItem` result is bridged to
     * a Kotlin `Map`. Derived from the constant rather than hardcoded as `"acct"`: the literal is
     * an implementation detail of the Security framework, and this way it cannot drift.
     */
    private val ACCOUNT_ATTRIBUTE: String by lazy {
        CFBridgingRelease(CFStringCreateCopy(kCFAllocatorDefault, kSecAttrAccount)) as String
    }
}

/**
 * A `CFMutableDictionary` for one `SecItem` call, with the CoreFoundation ownership handled.
 *
 * Manual, because there is no bridging shortcut: `SecItem*` takes a `CFDictionaryRef` whose keys
 * are the framework's own `CFStringRef` constants, so the dictionary cannot simply be an
 * `NSDictionary` built from Kotlin values. Every value this creates with `CFBridgingRetain` is
 * released in [close]; the dictionary's `kCFTypeDictionaryValueCallBacks` retains what it holds, so
 * releasing them immediately after the call is correct rather than merely tolerable.
 */
@OptIn(ExperimentalForeignApi::class)
private class SecQuery {
    private val owned = mutableListOf<CFTypeRef?>()

    val dict: CFMutableDictionaryRef = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )!!

    fun put(key: CFStringRef?, value: CFTypeRef?) {
        CFDictionaryAddValue(dict, key, value)
    }

    fun putString(key: CFStringRef?, value: String) {
        val ref = CFBridgingRetain(value)
        owned += ref
        CFDictionaryAddValue(dict, key, ref)
    }

    fun putBytes(key: CFStringRef?, value: ByteArray) {
        val ref = CFBridgingRetain(value.toNSData())
        owned += ref
        CFDictionaryAddValue(dict, key, ref)
    }

    fun close() {
        owned.forEach { it?.let { ref -> CFRelease(ref) } }
        owned.clear()
        CFRelease(dict)
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> query(block: (SecQuery) -> T): T {
    val q = SecQuery()
    return try {
        block(q)
    } finally {
        q.close()
    }
}
