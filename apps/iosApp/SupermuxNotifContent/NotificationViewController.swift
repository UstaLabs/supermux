//
//  NotificationViewController.swift
//  SupermuxNotifContent
//
//  Notification Content Extension principal class: draws the custom expanded view when a
//  collapsed supermux chat notification (category `supermux.chat`, set by the NSE) is
//  long-pressed / pulled down. It hosts `ExpandedNotificationView` (SwiftUI) and reads the
//  transcript straight from the notification's userInfo — which `SupermuxPushNSE` fills from
//  `PushGroupState.Grouped` — so there's no network or App Group access at expand time.
//
//  `UNNotificationExtensionDefaultContentHidden = true` (Info.plist, via project.yml) hides
//  the system's default title/body, so this view owns everything below the notification's
//  app-name/time header.
//

import UIKit
import SwiftUI
import UserNotifications
import UserNotificationsUI

class NotificationViewController: UIViewController, UNNotificationContentExtension {
    private var host: UIHostingController<ExpandedNotificationView>?
    private var lastLayoutWidth: CGFloat = 0

    func didReceive(_ notification: UNNotification) {
        let model = Self.parse(notification)

        // Rebuild the hosted SwiftUI view for this notification's content.
        if let host {
            host.willMove(toParent: nil)
            host.view.removeFromSuperview()
            host.removeFromParent()
        }
        let hosting = UIHostingController(rootView: ExpandedNotificationView(model: model))
        hosting.view.backgroundColor = .clear   // let the system notification material show through
        addChild(hosting)
        hosting.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosting.view)
        NSLayoutConstraint.activate([
            hosting.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosting.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosting.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosting.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        hosting.didMove(toParent: self)
        host = hosting
        updatePreferredContentSize()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Recompute the height once the real width is known — guarded on a width change so
        // setting preferredContentSize can't feed back into an endless layout loop.
        if abs(view.bounds.width - lastLayoutWidth) > 0.5 {
            updatePreferredContentSize()
        }
    }

    /// Size the extension to the SwiftUI content's natural height for the available width.
    private func updatePreferredContentSize() {
        guard let host else { return }
        let width = view.bounds.width > 0 ? view.bounds.width : preferredContentSize.width
        lastLayoutWidth = view.bounds.width
        let fitting = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
        if fitting.height > 0 {
            preferredContentSize = CGSize(width: width, height: ceil(fitting.height))
        }
    }

    // MARK: - Parse the notification's userInfo into the view model

    /// Read the transcript the NSE stashed (`sm_title` / `sm_count` / `sm_items`, the last
    /// being plist dicts `["t": text, "at": time]`, oldest → newest). Falls back to the
    /// notification body if the structured data is absent (an ungrouped or pre-update push).
    private static func parse(_ notification: UNNotification) -> ExpandedNotifModel {
        let content = notification.request.content
        let info = content.userInfo
        let title = (info["sm_title"] as? String) ?? content.title
        let count = (info["sm_count"] as? Int) ?? 0
        let raw = (info["sm_items"] as? [[String: Any]]) ?? []

        var messages: [NotifMessage] = raw.enumerated().map { index, dict in
            NotifMessage(text: (dict["t"] as? String) ?? "",
                         time: (dict["at"] as? String) ?? "",
                         isLatest: index == raw.count - 1)
        }
        if messages.isEmpty, !content.body.isEmpty {
            messages = [NotifMessage(text: content.body, time: "", isLatest: true)]
        }
        return ExpandedNotifModel(title: title, count: count, messages: messages)
    }
}
