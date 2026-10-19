import UserNotifications

/// Puts what a notification shows in place of the server's placeholder.
///
/// The server sends the notification of a new mail with a placeholder text and the sender,
/// subject and preview sealed for this device (`content`). This opens them with the device key
/// and nothing else -- no network, no session. When anything goes wrong the placeholder stays,
/// which still says that a mail came.
final class NotificationService: UNNotificationServiceExtension {

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var placeholder: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        placeholder = request.content

        guard
            let sealed = (request.content.userInfo["content"] as? String).flatMap({ Data(base64Encoded: $0) }),
            let key = try? PushKey.load(),
            let content = try? PushContentDecryptor.open(sealed, with: key)
        else {
            contentHandler(request.content)
            return
        }

        contentHandler(NotificationText.apply(content, to: request.content))
    }

    override func serviceExtensionTimeWillExpire() {
        // Opening takes milliseconds; should iOS still cut it short, the placeholder goes out.
        if let contentHandler, let placeholder { contentHandler(placeholder) }
    }
}

/// How ``PushContent`` is laid out in a notification, as the Mail app does: the sender as the
/// title, the subject above the preview -- or the subject alone when there is no preview.
enum NotificationText {
    static func apply(_ content: PushContent, to notification: UNNotificationContent) -> UNNotificationContent {
        let shown = (notification.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        let subject = content.subject ?? String(localized: "No subject")

        if let sender = content.sender { shown.title = sender }
        if let preview = content.preview {
            shown.subtitle = subject
            shown.body = preview
        } else {
            shown.subtitle = ""
            shown.body = subject
        }
        // Opened, the sealed content is of no further use; the ids the app opens the mail by stay.
        var userInfo = shown.userInfo
        userInfo.removeValue(forKey: "content")
        shown.userInfo = userInfo
        return shown
    }
}
