import ComposeApp
import UIKit
import UserNotifications

/// Where APNs and the notification center talk to the app. What it hears goes on to
/// `ApplePush` in the Kotlin framework, which decides what it means.
///
/// Asking for permission is not done here: the onboarding asks, through moko-permissions. The
/// token comes without it, and a push only shows once it is granted.
final class PushDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // On every start: the token may have changed, and the server is told again either way.
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        do {
            let key = try PushKey.loadOrCreate()
            ApplePush.shared.registered(
                token: deviceToken.map { String(format: "%02x", $0) }.joined(),
                isSandbox: ApsEnvironment.isSandbox,
                publicKey: key.publicKey.x963Representation.base64EncodedString()
            )
        } catch {
            // Without a key nothing could be opened; the next start tries again.
            print("Could not prepare the push key: \(error)")
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("Could not register for pushes: \(error)")
    }

    /// In the foreground as well: a mail is announced wherever the app is.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        let userInfo = response.notification.request.content.userInfo
        await MainActor.run { ApplePush.shared.opened(userInfo: userInfo) }
    }
}

/// Which APNs issued the token, read from the provisioning profile the build was signed with: a
/// development profile means the sandbox. An App Store build carries no profile and is production.
enum ApsEnvironment {
    static let isSandbox: Bool = {
        guard
            let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            let profile = try? String(contentsOf: url, encoding: .isoLatin1),
            let key = profile.range(of: "<key>aps-environment</key>")
        else { return false }
        let rest = profile[key.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        return rest.hasPrefix("<string>development</string>")
    }()
}
