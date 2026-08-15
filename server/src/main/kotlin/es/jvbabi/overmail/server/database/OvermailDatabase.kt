package es.jvbabi.overmail.server.database

import es.jvbabi.overmail.server.database.models.Users
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class OvermailDatabase {
    val postgresqldb = R2dbcDatabase.connect(
        url = "r2dbc:postgresql://postgres18.werkbank.studio:5432/overmail_overmail",
        driver = "postgresql",
        user = "werkbank",
        password = "werkbank"
    )

    suspend fun init() {
        query {
            SchemaUtils.create(Users)
        }
    }

    suspend fun query(block: suspend () -> Unit) {
        suspendTransaction(this.postgresqldb) {
            block()
        }
    }
}