package es.jvbabi.overmail.server.database

import es.jvbabi.overmail.server.database.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val POSTGRES_DRIVER = "org.postgresql.Driver"

/**
 * The one way into the database. Everything that reads or writes runs inside [query]: the DAO
 * entities in `database/models` only work while a transaction is open, and a reference read
 * outside one throws.
 */
class OvermailDatabase(private val database: Database) {

    constructor(config: DatabaseConfig) : this(
        Database.connect(
            url = config.jdbcUrl,
            driver = POSTGRES_DRIVER,
            user = config.user,
            password = config.password,
        )
    )

    suspend fun init() {
        query {
            SchemaUtils.create(Users)
            SchemaUtils.create(ImapAccounts)
            SchemaUtils.create(EmailUsers)
            SchemaUtils.create(Emails)
            SchemaUtils.create(EmailRecipients)
            SchemaUtils.create(EmailAiClassificationEvents)
            SchemaUtils.create(Labels, EmailLabels)
            SchemaUtils.create(Stacks, EmailStacks)
            SchemaUtils.create(EmailArchives)
        }
    }

    /**
     * Runs [block] in a transaction on [Dispatchers.IO]. The JDBC driver blocks the thread it is
     * called on, so this must not happen on the Netty event loop.
     */
    suspend fun <T> query(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(database) { block() } }
}
