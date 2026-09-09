import Foundation
// The iOS app links ONE Kotlin framework, SupermuxKit, which re-exports :shared; linking Shared
// as well would embed the :shared klib twice.
import SupermuxKit

// MARK: - KotlinByteArray bridging
//
// Both directions hop through `NSData`, which is a memcpy on the Kotlin side and a bulk copy on the
// Swift side. The obvious implementation — `get(index:)` / `set(index:value:)` in a loop — is ONE
// Objective-C message per byte, and this is the terminal's hot path in both directions: every pty
// chunk out (a `cat` of a big file is megabytes) and every keystroke and mouse-wheel burst in.
//
// `NSDataBytesKt` lives in `:shared` (re-exported by SupermuxKit), so the watch app's Shared.framework
// gets the same conversion; `:ios`'s `IosBytesKt` would not.

extension KotlinByteArray {
    func toUInt8() -> [UInt8] {
        [UInt8](NSDataBytesKt.nsDataOf(bytes: self) as Data)
    }
}

extension Array where Element == UInt8 {
    func toKotlin() -> KotlinByteArray {
        NSDataBytesKt.toByteArray(Data(self))
    }
}
