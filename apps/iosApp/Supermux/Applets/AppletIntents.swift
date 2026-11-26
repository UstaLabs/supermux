//
//  AppletIntents.swift
//  Supermux
//
//  #117 W1: applets as App Intents — the one-applet widget's "which applet", the Control Center / Lock Screen
//  control, and Spotlight ("Open Issues in supermux"). Opening goes through the App Group, not a URL scheme (custom
//  schemes are unreliable from a control): in the app, the intent hands the address straight to the shell; in the
//  extension, it leaves it in the group's defaults, which the app takes when it comes forward.
//
//  Compiled into BOTH the app and the widget extension (see project.yml). iOS 18: the control needs it, and the
//  widget extension's deployment target is 18.
//

import AppIntents
import Foundation
#if SUPERMUX_APP
import SupermuxKit
#endif

@available(iOS 18.0, *)
struct AppletEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Applet"
    static var defaultQuery = AppletQuery()

    /// "<host record id>/<applet name>".
    var id: String
    var title: String

    var host: String { String(id.split(separator: "/", maxSplits: 1).first ?? "") }
    var name: String { String(id.split(separator: "/", maxSplits: 1).dropFirst().first ?? "") }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)") }

    init(id: String, title: String) {
        self.id = id
        self.title = title
    }
}

@available(iOS 18.0, *)
struct AppletQuery: EntityStringQuery {
    func entities(for identifiers: [AppletEntity.ID]) async throws -> [AppletEntity] {
        all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [AppletEntity] { all() }

    func entities(matching string: String) async throws -> [AppletEntity] {
        all().filter { $0.title.localizedCaseInsensitiveContains(string) }
    }

    private func all() -> [AppletEntity] {
        AppletSnapshot.allApplets()
            .sorted { $0.applet.title.localizedCaseInsensitiveCompare($1.applet.title) == .orderedAscending }
            .map { AppletEntity(id: "\($0.host)/\($0.applet.name)", title: $0.applet.title) }
    }
}

/// The one-applet widget's setting.
@available(iOS 18.0, *)
struct SelectAppletIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Choose an applet"
    static var description = IntentDescription("The applet this widget shows.")

    @Parameter(title: "Applet")
    var applet: AppletEntity?
}

/// Opens an applet in supermux, full screen: the control's action and Spotlight's result.
@available(iOS 18.0, *)
struct OpenAppletIntent: ControlConfigurationIntent {
    static var title: LocalizedStringResource = "Open applet"
    static var description = IntentDescription("Opens an applet in supermux.")
    static var openAppWhenRun = true

    @Parameter(title: "Applet")
    var applet: AppletEntity?

    init() {}

    init(applet: AppletEntity) {
        self.applet = applet
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let applet else { return .result() }
        let uri = AppletSnapshot.uri(host: applet.host, name: applet.name)
        #if SUPERMUX_APP
        // Running in the app: straight to the shell, which opens it full screen.
        IosAppState.shared.setOpenedUrl(url: uri)
        #else
        UserDefaults(suiteName: AppletSnapshot.appGroup)?.set(uri, forKey: AppletSnapshot.pendingKey)
        #endif
        return .result()
    }
}

/// #165: a widget button or toggle (Home Screen or Lock Screen): runs the applet's action through the paired host,
/// from the widget extension, with no app on screen. WidgetKit reloads the widget when it returns, outside the budget;
/// the answer (the applet's new widget) is already in the snapshot by then. A failure shows on the widget's next draw.
@available(iOS 18.0, *)
struct RunWidgetActionIntent: AppIntent {
    static var title: LocalizedStringResource = "Run an applet action"
    static var description = IntentDescription("A button on an applet's widget.")
    static var isDiscoverable = false

    @Parameter(title: "Host") var host: String
    @Parameter(title: "Applet") var applet: String
    @Parameter(title: "Action") var action: String
    @Parameter(title: "Arguments") var args: String

    init() {}

    init(host: String, applet: String, action: String, args: String) {
        self.host = host
        self.applet = applet
        self.action = action
        self.args = args
    }

    func perform() async throws -> some IntentResult {
        do {
            try await WidgetHostAccess.run(host: host, applet: applet, action: action, argsJSON: args)
            AppletSnapshot.update(host: host, applet: applet) { $0.removeValue(forKey: "widgetError") }
        } catch {
            // Shown on the widget (it can't show an alert); cleared by the next good tap or snapshot.
            let why = error.localizedDescription
            AppletSnapshot.update(host: host, applet: applet) { $0["widgetError"] = why }
        }
        return .result()
    }
}
