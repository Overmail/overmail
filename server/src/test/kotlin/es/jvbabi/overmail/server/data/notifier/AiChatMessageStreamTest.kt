package es.jvbabi.overmail.server.data.notifier

import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AiChatMessageStreamTest {

    @Test
    fun `snapshot carries the text so far and where it ends`() {
        val stream = AiChatMessageStream()
        stream.append("Hello")
        stream.append(" world")

        val snapshot = stream.snapshot()
        assertEquals("Hello world", snapshot.content)
        assertEquals(2, snapshot.nextChunk)
        assertTrue(!snapshot.completed)
    }

    @Test
    fun `a reader attaching mid-run sees the text so far and everything after it`() = runTest {
        val stream = AiChatMessageStream()
        stream.append("before ")

        var snapshot = AiChatMessageStream.Snapshot("", 0, false, 0)
        val reader = async {
            stream.events
                .onSubscription { snapshot = stream.snapshot() }
                .takeWhile { event -> event !is AiChatStreamEvent.Completed }
                .toList()
        }

        // The reader is attached once its snapshot was taken inside onSubscription.
        while (snapshot.nextChunk == 0) kotlinx.coroutines.yield()

        stream.append("after")
        stream.complete()

        val events = reader.await().filterIsInstance<AiChatStreamEvent.Chunk>()
        assertEquals("before ", snapshot.content)
        assertEquals(listOf("after"), events.filter { it.index >= snapshot.nextChunk }.map { it.text })
    }

    @Test
    fun `output tokens add up across turns`() {
        val stream = AiChatMessageStream()
        stream.addOutputTokens(12)
        stream.addOutputTokens(30)
        // Nothing to count, and nothing after the answer is done.
        stream.addOutputTokens(0)

        assertEquals(42, stream.snapshot().tokensOutput)

        stream.complete()
        stream.addOutputTokens(5)
        assertEquals(42, stream.snapshot().tokensOutput)
    }

    @Test
    fun `nothing is appended after the answer is complete`() {
        val stream = AiChatMessageStream()
        stream.append("done")
        stream.complete()
        stream.append(" and more")

        assertEquals("done", stream.snapshot().content)
        assertTrue(stream.snapshot().completed)
    }

    @Test
    fun `a closed stream is gone from the notifier`() {
        val notifier = AiChatStreamNotifier()
        val messageId = Uuid.random()

        val stream = notifier.open(messageId)
        assertEquals(stream, notifier.of(messageId))

        notifier.close(messageId)
        assertEquals(null, notifier.of(messageId))
    }
}
