//
//  AppletShortcuts.swift
//  Supermux
//
//  #117 W1: applets in Spotlight and Siri ("Open Issues in supermux"). App only: Spotlight reads App Shortcuts from
//  the app bundle. `updateAppShortcutParameters()` runs whenever the widgets' snapshot changes (SwiftBridge), so new
//  applets show up by name.
//

import AppIntents

@available(iOS 18.0, *)
struct SupermuxAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: OpenAppletIntent(),
            phrases: [
                "Open \(\.$applet) in \(.applicationName)",
                "Open an applet in \(.applicationName)",
            ],
            shortTitle: "Open applet",
            systemImageName: "square.grid.2x2"
        )
    }
}
