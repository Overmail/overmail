package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.Memory
import kotlin.uuid.Uuid

/**
 * The core memories of one run, under the handles the model refers to them by: `K1`, `K2`.
 *
 * Handles rather than ids, for the reason the mails and the threads have them too: a model asked to
 * copy thirty-six characters of hexadecimal gets one wrong eventually, and a handle that was never
 * handed out resolves to nothing -- which makes "ask about somebody else's memory" a tool error
 * instead of a possibility.
 *
 * Handed out per run and not stored. A stored number would have to be kept unique per reader,
 * renumbered when a memory is closed, and would still mean nothing to anybody but the model. What
 * matters is only that the number is stable while a conversation is going on, and that the order is
 * the same every run -- which is why the memories come oldest first.
 *
 * One of these per run, and it grows: a memory written during the run gets the next handle, so the
 * model can put a detail under something it only just learned.
 */
class MemoryHandles(core: List<Memory> = emptyList()) {

    private val byHandle = LinkedHashMap<String, Memory>()

    init {
        core.forEach { register(it) }
    }

    /** The handle of [memory], handing out a new one where it has none yet. */
    fun register(memory: Memory): String {
        byHandle.entries.firstOrNull { it.value.id == memory.id }?.let { known ->
            // Refreshed rather than kept: a memory that was closed during the run must not read as
            // open on the next line about it.
            byHandle[known.key] = memory

            return known.key
        }

        val handle = "K${byHandle.size + 1}"
        byHandle[handle] = memory

        return handle
    }

    /** The memory a handle names, or null where nothing was handed out under it. */
    operator fun get(handle: String): Memory? = byHandle[handle.uppercase()]

    /** The id a handle names, for the calls that only need that. */
    fun idOf(handle: String): Uuid? = get(handle)?.id

    /**
     * The lines that go in front of the model: one per core memory, with its handle, its topic, what
     * it says, the stretch it covers and whose memory it is.
     *
     * Everything a step needs to decide whether to ask for more, and nothing more than that. The
     * details behind each one are what the handle is for -- see the recall tool -- and putting them
     * here instead would be the whole point of this shape thrown away.
     */
    fun lines(): List<String> = byHandle
        .filterValues { it.isCore }
        .map { (handle, memory) ->
            val period = memory.periodAsText().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            val whose = if (memory.createdByAgent) "(agent)" else "(user)"

            "$handle · ${memory.topic ?: "?"} · ${memory.content}$period $whose"
        }
}
