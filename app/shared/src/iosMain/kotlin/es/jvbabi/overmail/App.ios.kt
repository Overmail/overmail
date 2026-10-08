@file:OptIn(ExperimentalForeignApi::class)

package es.jvbabi.overmail

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import es.jvbabi.overmail.domain.model.DeviceInfo
import es.jvbabi.overmail.ui.theme.darkScheme
import es.jvbabi.overmail.ui.theme.lightScheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticPattern
import kotlin.random.Random
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toPath
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPopoverArrowDirectionAny
import platform.UIKit.UIScreen
import platform.UIKit.popoverPresentationController
import platform.posix.uname
import platform.posix.utsname
import kotlin.time.Instant

actual fun imageCacheDirectory(context: PlatformContext): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$caches/avatars".toPath()
}

actual fun emailBodyCacheDirectory(): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$caches/email-bodies".toPath()
}

actual fun emailPictureCacheDirectory(): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$caches/email-pictures".toPath()
}

actual fun attachmentCacheDirectory(): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$caches/attachments".toPath()
}

/** Asked for the screen to show the preview over; UIKit does not keep the delegate itself alive. */
private class DocumentDelegate : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
    override fun documentInteractionControllerViewControllerForPreview(
        controller: UIDocumentInteractionController,
    ): UIViewController = checkNotNull(topViewController())

    override fun documentInteractionControllerDidEndPreview(controller: UIDocumentInteractionController) {
        openDocument = null
    }

    override fun documentInteractionControllerDidDismissOpenInMenu(controller: UIDocumentInteractionController) {
        openDocument = null
    }
}

/** The document on screen and its delegate, held until it is closed: both go away when let go of. */
private var openDocument: Pair<UIDocumentInteractionController, DocumentDelegate>? = null

/** What is on top, so a preview is not presented under a sheet that is already up. */
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

actual fun openFile(file: Path, contentType: String): Boolean {
    val view = topViewController()?.view ?: return false
    val controller = UIDocumentInteractionController.interactionControllerWithURL(NSURL.fileURLWithPath(file.toString()))
    val delegate = DocumentDelegate()
    controller.delegate = delegate
    openDocument = controller to delegate

    // Quick Look shows most of what a mail brings; for the rest, the apps that can open it.
    val shown = controller.presentPreviewAnimated(true) ||
        controller.presentOpenInMenuFromRect(view.bounds, inView = view, animated = true)
    if (!shown) openDocument = null
    return shown
}

actual fun openUrl(url: String) {
    val nsUrl = NSURL(string = url)
    val safariViewController = SFSafariViewController(nsUrl)

    val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController

    rootViewController?.presentViewController(
        viewControllerToPresent = safariViewController,
        animated = true,
        completion = null
    )
}

actual fun shareUrl(url: String, title: String?) {
    val itemsToShare = mutableListOf<Any>(NSURL.URLWithString(url)!!)

    val activityViewController = UIActivityViewController(
        activityItems = itemsToShare,
        applicationActivities = null
    )

    val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController

    activityViewController.popoverPresentationController?.apply {
        val viewBounds = rootViewController?.view?.bounds ?: UIScreen.mainScreen.bounds

        sourceView = rootViewController?.view
        sourceRect = CGRectMake(
            x = viewBounds.useContents { size.width } / 2,
            y = viewBounds.useContents { size.height },
            width = 0.0,
            height = 0.0
        )
        permittedArrowDirections = UIPopoverArrowDirectionAny
    }

    rootViewController?.presentViewController(
        viewControllerToPresent = activityViewController,
        animated = true,
        completion = null
    )
}

actual fun getClipboardText(): String? = UIPasteboard.generalPasteboard.string

/** Kept until the firework is over: an engine that is let go of stops what it plays. */
private var fireworkEngine: CHHapticEngine? = null

@OptIn(ExperimentalForeignApi::class)
actual fun hapticFirework() {
    // Core Haptics is what makes single taps; a device without it only knows the system's buzz.
    if (!CHHapticEngine.capabilitiesForHardware().supportsHaptics) return
    val events = buildList {
        var time = 0.0
        // The launch, a rising rumble of soft taps.
        repeat(Random.nextInt(3, 6)) { index ->
            add(tap(time, intensity = 0.3 + index * 0.1, sharpness = 0.1))
            time += Random.nextDouble(0.025, 0.045)
        }
        // The bursts: sharp pops and faint crackles scattered over half a second.
        repeat(Random.nextInt(8, 14)) {
            val isPop = Random.nextDouble() < 0.35
            add(
                tap(
                    time,
                    intensity = if (isPop) Random.nextDouble(0.6, 1.0) else Random.nextDouble(0.2, 0.7),
                    sharpness = if (isPop) Random.nextDouble(0.5, 1.0) else Random.nextDouble(0.2, 0.6),
                )
            )
            time += Random.nextDouble(0.015, 0.07)
        }
    }
    val engine = fireworkEngine ?: CHHapticEngine(null).also { fireworkEngine = it }
    engine.startAndReturnError(null)
    val pattern = CHHapticPattern(events = events, parameters = emptyList<Any>(), error = null)
    engine.createPlayerWithPattern(pattern, null)?.startAtTime(0.0, null)
}

private fun tap(time: Double, intensity: Double, sharpness: Double) = CHHapticEvent(
    eventType = CHHapticEventTypeHapticTransient,
    parameters = listOf(
        CHHapticEventParameter(parameterID = CHHapticEventParameterIDHapticIntensity, value = intensity.toFloat()),
        CHHapticEventParameter(parameterID = CHHapticEventParameterIDHapticSharpness, value = sharpness.toFloat()),
    ),
    relativeTime = time,
)

/** `UIDevice.model` is only "iPhone"; the machine name is the exact model, e.g. "iPhone16,2". */
actual fun deviceInfo(): DeviceInfo = DeviceInfo(
    platform = "ios",
    device = memScoped {
        val system = alloc<utsname>()
        uname(system.ptr)
        system.machine.toKString()
    },
    manufacturer = "Apple",
    os = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
)

/** iOS has no system-wide accent to derive a scheme from, so this is the app's own. */
@Composable
actual fun dynamicTheme(dark: Boolean): ColorScheme = if (dark) darkScheme else lightScheme

actual fun formatDateTime(instant: Instant, skeleton: String, languageTag: String): String {
    // A new formatter starts out in the device's time zone.
    val formatter = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = languageTag)
        setLocalizedDateFormatFromTemplate(skeleton)
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
}
