package es.jvbabi.overmail.server.database

import es.jvbabi.overmail.server.database.models.Archived
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailSpam
import es.jvbabi.overmail.server.database.models.Filters
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.EmailThreads
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.database.models.Threads
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
            // Before the email users: their `avatar_id` points here.
            SchemaUtils.create(EmailAvatars)
            // Columns added to an existing address book come in here, `create` alone would skip
            // them -- same reason as for the mails below.
            SchemaUtils.createMissingTablesAndColumns(EmailUsers)
            SchemaUtils.createMissingTablesAndColumns(Emails)
            SchemaUtils.create(EmailRecipients)
            SchemaUtils.create(Tags)
            SchemaUtils.create(EmailTags)
            SchemaUtils.create(Threads)
            SchemaUtils.create(EmailThreads)
            SchemaUtils.create(Archived)
            SchemaUtils.create(Filters)
            // After the filters: `filter_id` points there.
            SchemaUtils.create(EmailSpam)
        }
    }

    suspend fun <T> query(block: suspend R2dbcTransaction.() -> T): T {
        return suspendTransaction(this.postgresqldb) { block() }
    }
}
