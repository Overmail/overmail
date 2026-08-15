package es.jvbabi.overmail.server.database.changes

import es.jvbabi.overmail.server.database.DatabaseConfig
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.replication.ReplicationRequest
import io.r2dbc.postgresql.replication.ReplicationSlotRequest
import io.r2dbc.postgresql.replication.ReplicationStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

private const val OUTPUT_PLUGIN = "test_decoding"
private val STATUS_INTERVAL = 10.seconds
private val RECONNECT_DELAY = 5.seconds

/** `table public.imap_accounts: INSERT: id[uuid]:'…'` */
private val CHANGE_LINE = Regex("""^table (?:[^.]+\.)?"?([^":]+)"?: (?:INSERT|UPDATE|DELETE)""")

/**
 * Streams committed row changes straight out of the write ahead log via logical replication,
 * so no application side triggers or notifications are needed.
 *
 * Requires `wal_level = logical` and a database user with the `REPLICATION` attribute. The
 * replication slot is temporary: it is dropped as soon as the connection ends, so a crashed
 * server cannot make postgres retain WAL forever.
 *
 * The stream holds its own dedicated connection and reconnects on failure. Every successful
 * connect bumps [connectionGeneration], which makes [changesOf] re-emit, so consumers resync
 * after a connection loss during which changes were missed.
 */
class PostgresChangeStream(
    private val config: DatabaseConfig,
    scope: CoroutineScope,
) {
    private val connectionGeneration = MutableStateFlow(0)

    private val connectionFactory by lazy {
        PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(config.host)
                .port(config.port)
                .database(config.database)
                .username(config.user)
                .password(config.password)
                .applicationName("overmail-change-stream")
                .build()
        )
    }

    private val changedTables: SharedFlow<String> = flow {
        val connection = connectionFactory.replication().awaitSingle()
        try {
            val slot = connection.createSlot(
                ReplicationSlotRequest.logical()
                    .slotName("overmail_${Uuid.random().toHexString().take(16)}")
                    .outputPlugin(OUTPUT_PLUGIN)
                    .temporary()
                    .build()
            ).awaitSingle()

            val stream = connection.startReplication(
                ReplicationRequest.logical(slot)
                    .slotOption("skip-empty-xacts", true)
                    .statusInterval(STATUS_INTERVAL.toJavaDuration())
                    .build()
            ).awaitSingle()

            connectionGeneration.update { it + 1 }

            try {
                emitAll(
                    stream
                        .map { buffer -> buffer.toString(Charsets.UTF_8) }
                        .asFlow()
                        .onEach { stream.confirmProcessed() }
                        .mapNotNull { message -> CHANGE_LINE.find(message)?.groupValues?.get(1) }
                )
            } finally {
                withContext(NonCancellable) { stream.close().awaitFirstOrNull() }
            }
            error("replication stream closed")
        } finally {
            withContext(NonCancellable) { connection.close().awaitFirstOrNull() }
        }
    }.retryWhen { cause, _ ->
        System.err.println("change stream disconnected (${cause.message}), retrying in $RECONNECT_DELAY")
        delay(RECONNECT_DELAY)
        true
    }.shareIn(scope, SharingStarted.Eagerly)

    /**
     * Emits immediately on subscription, on every (re)connect of the replication stream and on
     * every committed change to any of [tables]. Emitting without waiting for the stream keeps
     * consumers working (without live updates) if replication is unavailable.
     */
    fun changesOf(vararg tables: Table): Flow<Unit> {
        val tableNames = tables.map { it.tableName.substringAfterLast('.').trim('"') }.toSet()
        return merge(
            connectionGeneration,
            changedTables.filter { it in tableNames },
        ).map { }
    }
}

/** Lets postgres release the WAL we already handed to the consumers. */
private fun ReplicationStream.confirmProcessed() {
    setFlushedLSN(lastReceiveLSN)
    setAppliedLSN(lastReceiveLSN)
}
