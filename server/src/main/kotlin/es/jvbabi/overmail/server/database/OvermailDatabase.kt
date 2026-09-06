package es.jvbabi.overmail.server.database

import es.jvbabi.overmail.server.database.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; prettyPrint = false }
    }

    suspend fun init() {
        query {
            SchemaUtils.create(Users)
            SchemaUtils.create(ImapAccounts, ImapAccountFolderSyncs)
            // `create` only ever adds missing *tables*, so a column added to a table that already
            // exists would never reach a database that has been running. There is no migration
            // tool here, and this is the one table that has gained a column since.
            SchemaUtils.createMissingTablesAndColumns(ImapAccounts)
            SchemaUtils.create(EmailAvatars)
            SchemaUtils.create(EmailUsers)
            SchemaUtils.create(Emails)
            // After the mails: the preview is keyed by one.
            SchemaUtils.create(EmailPreviews)
            SchemaUtils.create(EmailRecipients)
            SchemaUtils.create(EmailAiClassificationEvents)
            SchemaUtils.create(Labels, EmailLabels)
            SchemaUtils.create(Stacks, EmailStacks)
            SchemaUtils.create(EmailArchives)
            SchemaUtils.create(AiChats, AiChatMessages)
            SchemaUtils.create(Knowledges)
            SchemaUtils.create(Shares)
            // Like `ImapAccounts` above: the password columns came after the table did, and
            // `create` would not add them to a database that already has a `shares`.
            SchemaUtils.createMissingTablesAndColumns(Shares)
        }
    }

    /**
     * Runs [block] in a transaction on [Dispatchers.IO]. The JDBC driver blocks the thread it is
     * called on, so this must not happen on the Netty event loop.
     */
    suspend fun <T> query(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(database) { block() } }
}
