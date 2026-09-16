@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.domain.repository.ApplicationRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

class IosApplicationRepository : ApplicationRepository {

    @OptIn(ExperimentalNativeApi::class)
    override val isDebugBuild: Boolean = Platform.isDebugBinary

    // Read on every access rather than cached, so a language change at runtime is picked up.
    // preferredLanguages carries region-qualified tags ("de-DE"), and the changelog assets are
    // keyed by language alone.
    override val language: String
        get() = (NSLocale.preferredLanguages.firstOrNull() as? String)
            ?.substringBefore('-')
            ?.lowercase()
            ?: NSLocale.currentLocale.languageCode.lowercase()

    override fun getApplicationForegroundState(): Flow<Boolean> = callbackFlow {
        trySend(true)

        val observer = object : NSObject() {
            @ObjCAction
            fun didBecomeActive() { trySend(true) }

            @ObjCAction
            fun willResignActive() { trySend(false) }
        }

        NSNotificationCenter.defaultCenter.addObserver(
            observer = observer,
            selector = NSSelectorFromString("didBecomeActive"),
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
        )
        NSNotificationCenter.defaultCenter.addObserver(
            observer = observer,
            selector = NSSelectorFromString("willResignActive"),
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
        )

        awaitClose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }.distinctUntilChanged()
}
