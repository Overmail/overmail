@file:OptIn(ExperimentalForeignApi::class)

package es.jvbabi.overmail.di

import androidx.room.Room
import androidx.room.RoomDatabase
import es.jvbabi.overmail.data.database.OvermailDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Empty: everything iOS contributes is registered from `MainViewController`, and the one thing that
 * lives in a platform module — the in-app updater — is Android-only.
 */
actual fun platformModule(): Module = module { }

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
