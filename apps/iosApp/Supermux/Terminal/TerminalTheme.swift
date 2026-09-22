import SwiftUI

/// The SwiftTerm surface colours. They must match the web's `--cmux-terminal` and its foreground —
/// a silent drift between platforms is exactly the bug nobody would look for — so they live here,
/// next to their only consumer (`ComposeTerminalVendor`), rather than on a SwiftUI theme type.
enum TerminalTheme {
    /// Dark terminal surface — matches the web `--cmux-terminal`.
    static let background = Color(red: 0.07, green: 0.07, blue: 0.09)
    static let foreground = Color(red: 0.90, green: 0.90, blue: 0.92)
}
