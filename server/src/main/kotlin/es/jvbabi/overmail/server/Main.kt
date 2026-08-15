package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val db = OvermailDatabase()
        db.init()
    }
}