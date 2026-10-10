@file:OptIn(ExperimentalForeignApi::class)

package es.jvbabi.overmail.di

import androidx.room.Room
import androidx.room.RoomDatabase
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.picture.EmailPictureRenderer
import es.jvbabi.overmail.data.picture.IosEmailPictureRenderer
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * The renderer of mail pictures, and a push token that never comes. Everything else iOS contributes is registered from
 * `MainViewController`, and the in-app updater is Android-only.
 */
actual fun platformModule(): Module = module {
    single<EmailPictureRenderer> { IosEmailPictureRenderer() }

    // No push on iOS yet, so there is never a token to register.
    single<PushTokenRepository> {
        object : PushTokenRepository {
            override fun getToken(): Flow<String> = emptyFlow()
        }
    }
}

actual fun getDatabaseBuilder(): RoomDatabase.Builder<OvermailDatabase> {
    val dbFilePath = documentDirectory() + "/overmail.db"
    return Room.databaseBuilder<OvermailDatabase>(
        name = dbFilePath,
    )
}

private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}
