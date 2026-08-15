package es.jvbabi.overmail.server.database

import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Users
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class OvermailDatabase(
    config: DatabaseConfig,
) {
    val postgresqldb = R2dbcDatabase.connect(
        url = config.r2dbcUrl,
        driver = "postgresql",
        user = config.user,
        password = config.password
    )

    suspend fun init() {
        query {
            SchemaUtils.create(Users)
            SchemaUtils.create(ImapAccounts)
            SchemaUtils.create(EmailUsers)
            SchemaUtils.create(Emails)
            SchemaUtils.create(EmailRecipients)
        }
    }

    suspend fun <T> query(block: suspend R2dbcTransaction.() -> T): T {
        return suspendTransaction(this.postgresqldb) { block() }
    }
}
