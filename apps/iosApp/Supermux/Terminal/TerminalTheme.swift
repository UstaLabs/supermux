import SwiftUI

/// The SwiftTerm surface colours, in the ONE place both shells can reach.
///
/// They used to live on `Theme`, the SwiftUI design system — which after the H6 cutover is Mac-only
/// (`SupermuxMacUI/DesignSystem/Theme.swift`), while the terminal host it feeds is compiled by the
/// iOS target too. Rather than duplicate the two constants (they must match the web's
/// `--cmux-terminal` and its foreground, and a silent drift between platforms is exactly the bug
/// nobody would look for), they moved down here next to their only real consumer. `Theme` re-exposes
/// them for the Mac shell's call sites.
enum TerminalTheme {
    /// Dark terminal surface — matches the web `--cmux-terminal`.
    static let background = Color(red: 0.07, green: 0.07, blue: 0.09)
    static let foreground = Color(red: 0.90, green: 0.90, blue: 0.92)
}
