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
//  Sizing: the hosting controller keeps its own `preferredContentSize` in sync with the
//  SwiftUI content (`sizingOptions = .preferredContentSize`, iOS 16+) and we forward that up
//  as this extension's size via `preferredContentSizeDidChange`. (An earlier hand-rolled
//  `sizeThatFits` pass left the expanded view zero-height / blank — that's what this replaces.)
//

import UIKit
import SwiftUI
import UserNotifications
import UserNotificationsUI

class NotificationViewController: UIViewController, UNNotificationContentExtension {
    private var hosting: UIHostingController<ExpandedNotificationView>?

    override func viewDidLoad() {
        super.viewDidLoad()
        // Clear so the system notification material shows behind the SwiftUI content.
        view.backgroundColor = .clear
        NSLog("[supermux CE] viewDidLoad bounds=%.0fx%.0f", view.bounds.width, view.bounds.height)
    }

    func didReceive(_ notification: UNNotification) {
        let model = Self.parse(notification)
        NSLog("[supermux CE] didReceive title=%{public}@ count=%d msgs=%d",
              model.title, model.count, model.messages.count)
        let root = ExpandedNotificationView(model: model)

        // Reuse the hosting controller across updates — just swap its root view.
        if let hosting {
            hosting.rootView = root
            return
        }

        let controller = UIHostingController(rootView: root)
        controller.view.backgroundColor = .clear
        controller.sizingOptions = .preferredContentSize
        addChild(controller)
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(controller.view)
        NSLayoutConstraint.activate([
            controller.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            controller.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            controller.view.topAnchor.constraint(equalTo: view.topAnchor),
            controller.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        controller.didMove(toParent: self)
        hosting = controller
    }

    /// The hosted SwiftUI controller reports its ideal size here (driven by `sizingOptions`);
    /// forward it as this content extension's own size so the expanded notification is tall
    /// enough to show the whole transcript.
    override func preferredContentSizeDidChange(forChildContentContainer container: UIContentContainer) {
        super.preferredContentSizeDidChange(forChildContentContainer: container)
        preferredContentSize = container.preferredContentSize
        NSLog("[supermux CE] childSize=%.0fx%.0f",
              container.preferredContentSize.width, container.preferredContentSize.height)
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
