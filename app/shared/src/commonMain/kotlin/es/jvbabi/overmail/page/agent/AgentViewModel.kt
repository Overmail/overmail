package es.jvbabi.overmail.page.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.AiAnswerEvent
import es.jvbabi.overmail.domain.model.AiChat
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.page.agent.markdown.EntityKind
import es.jvbabi.overmail.page.agent.markdown.entityIds
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.LabelReference
import es.jvbabi.overmail.domain.model.toReference
import es.jvbabi.overmail.domain.repository.AiChatRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.domain.usecase.account.GetCurrentAccountUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = Logger.withTag("AgentViewModel")

/**
 * The assistant: the chat list, the open chat and its messages, and the prompt. The web app's
 * `AiChatViewModel`, minus the references in a prompt and the modes.
 */
class AgentViewModel(
    private val aiChatRepository: AiChatRepository,
    private val labelsRepository: LabelsRepository,
    private val emailsRepository: EmailsRepository,
    private val participantsRepository: ParticipantsRepository,
    private val getCurrentAccountUseCase: GetCurrentAccountUseCase,
) : ViewModel() {
    val state: StateFlow<AgentState>
        field = MutableStateFlow(AgentState())

    /** Loads the open chat and follows its running answers; replaced when another chat is opened. */
    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            getCurrentAccountUseCase().collectLatest { account ->
                state.update { AgentState(account = account) }
                chatJob?.cancel()
                if (account == null) return@collectLatest
                aiChatRepository.getChats(account).collect { chats -> state.update { it.copy(chats = chats) } }
            }
        }

        // What the tool calls of the open chat point at, looked up as they come in.
        viewModelScope.launch {
            state
                .map { state -> state.account?.let { it to referencesOf(state.messages) } }
                .distinctUntilChanged()
                .collectLatest { request ->
                    val (account, references) = request ?: return@collectLatest
                    val labels = cachedOrLookedUp(
                        ids = references.labelIds,
                        cached = labelsRepository.getByIds(references.labelIds.toList(), account)
                            .map { labels -> labels.associate { it.id to it.toReference() } },
                        lookUp = { ids -> labelsRepository.lookUp(ids, account) },
                        idOf = { it.id },
                    )
                    val persons = cachedOrLookedUp(
                        ids = references.personIds,
                        cached = participantsRepository.getByIds(references.personIds.toList(), account)
                            .map { participants -> participants.associateBy { it.id } },
                        lookUp = { ids -> participantsRepository.lookUp(ids, account) },
                        idOf = { it.id },
                    )
                    val emails = references.emailIds
                        .map { id -> emailsRepository.getEmail(id, account).map { id to it } }
                        .let { flows -> if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() } }
                        .map { emails -> emails.mapNotNull { (id, email) -> email?.let { id to it } }.toMap() }
                    combine(labels, emails, persons) { labels, emails, persons -> AgentReferences(labels, emails, persons) }
                        .collect { references -> state.update { it.copy(references = references) } }
                }
        }
    }

    private data class ReferenceIds(val labelIds: Set<Uuid>, val emailIds: Set<Uuid>, val personIds: Set<Uuid>)

    /** What the tool calls name by their attributes, and the text by its elements. */
    private fun referencesOf(messages: List<AiChatMessage>): ReferenceIds {
        val answers = messages.filterIsInstance<AiChatMessage.Assistant>()
        val calls = answers
            .flatMap { agentBlocks(it.content) }
            .filterIsInstance<AgentBlock.Tools>()
            .flatMap { it.calls }
        val entities = answers.map { entityIds(it.content) }
        fun ids(attribute: String, kind: EntityKind) =
            calls.mapNotNull { call -> call.attribute(attribute)?.let(Uuid::parseOrNull) }.toSet() +
                entities.flatMap { it[kind].orEmpty() }
        return ReferenceIds(
            labelIds = ids("labelId", EntityKind.Label),
            emailIds = ids("emailId", EntityKind.Email),
            personIds = entities.flatMap { it[EntityKind.Person].orEmpty() }.toSet(),
        )
    }

    /**
     * [ids] out of the [cached] ones, and the ones the cache does not hold from the server, once:
     * a label the assistant has just made, a sender nobody has searched for.
     */
    private fun <T> cachedOrLookedUp(
        ids: Set<Uuid>,
        cached: Flow<Map<Uuid, T>>,
        lookUp: suspend (List<Uuid>) -> Result<List<T>>,
        idOf: (T) -> Uuid,
    ): Flow<Map<Uuid, T>> = flow {
        if (ids.isEmpty()) {
            emit(emptyMap())
            return@flow
        }
        val fetched = MutableStateFlow<Map<Uuid, T>>(emptyMap())
        coroutineScope {
            launch {
                val missing = ids - cached.first().keys
                if (missing.isEmpty()) return@launch
                lookUp(missing.toList())
                    .onSuccess { found -> fetched.value = found.associateBy(idOf) }
                    .onFailure { e -> logger.w(e) { "Could not look up what the chat refers to" } }
            }
            emitAll(combine(cached, fetched) { cached, fetched -> fetched + cached })
        }
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.SetInput -> state.update { it.copy(input = event.input) }
            AgentEvent.NewChat -> openChat(null)
            is AgentEvent.SelectChat -> openChat(event.chatId)
            AgentEvent.Send -> {
                val text = state.value.input.trim()
                if (text.isEmpty()) return
                send(state.value.currentChatId, text, fromInput = true)
            }
            is AgentEvent.Retry -> retry(event.messageId)
            // Asked from somewhere else, the search for instance: a question of its own.
            is AgentEvent.Ask -> {
                if (event.text.isBlank()) return
                openChat(null)
                send(null, event.text.trim(), fromInput = false)
            }
        }
    }

    private fun openChat(chatId: Uuid?) {
        chatJob?.cancel()
        state.update { it.copy(currentChatId = chatId, messages = emptyList(), pendingQuestion = null, error = null) }
        if (chatId != null) chatJob = viewModelScope.launch { load(chatId) }
    }

    /**
     * Sends [text] into [chatId]. It shows up as asked right away, see [AgentState.pendingQuestion],
     * and the prompt is emptied with it; a question that did not get through goes back into the
     * prompt it came from, [fromInput].
     */
    private fun send(chatId: Uuid?, text: String, fromInput: Boolean) {
        val account = state.value.account ?: return
        if (state.value.isAnswering) return

        state.update {
            it.copy(
                isSending = true,
                pendingQuestion = text,
                input = if (fromInput) "" else it.input,
                error = null,
            )
        }
        viewModelScope.launch {
            aiChatRepository.send(chatId, text, account)
                .onSuccess { sent ->
                    chatJob?.cancel()
                    state.update { it.copy(currentChatId = sent.chatId, isSending = false) }
                    // The question stays shown as pending until the chat is loaded with it in it.
                    chatJob = viewModelScope.launch { load(sent.chatId) }
                }
                .onFailure { e ->
                    logger.e(e) { "Could not send the question" }
                    state.update {
                        it.copy(
                            isSending = false,
                            pendingQuestion = null,
                            input = if (fromInput && it.input.isEmpty()) text else it.input,
                            error = AgentError.Send,
                        )
                    }
                }
        }
    }

    /**
     * Asks the question before [messageId] again. The old answer is replaced rather than kept, so
     * the chat is loaded anew: the message comes back pending and is followed like a new one.
     */
    private fun retry(messageId: Uuid) {
        val account = state.value.account ?: return
        val chatId = state.value.currentChatId ?: return
        if (state.value.isAnswering) return

        state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            aiChatRepository.retry(chatId, messageId, account)
                .onSuccess {
                    state.update { it.copy(isSending = false) }
                    chatJob?.cancel()
                    chatJob = viewModelScope.launch { load(chatId) }
                }
                .onFailure { e ->
                    logger.e(e) { "Could not retry answer $messageId" }
                    state.update { it.copy(isSending = false, error = AgentError.Retry) }
                }
        }
    }

    /** The messages of [chatId], and every answer in it that is still being written as it grows. */
    private suspend fun load(chatId: Uuid) {
        val account = state.value.account ?: return
        val messages = aiChatRepository.getMessages(chatId, account).getOrElse { e ->
            logger.e(e) { "Could not load chat $chatId" }
            state.update { it.copy(error = AgentError.Load, pendingQuestion = null) }
            return
        }
        state.update { it.copy(messages = messages, pendingQuestion = null) }

        // Children of the chat's job, so opening another chat stops following this one's answers.
        coroutineScope {
            messages.filterIsInstance<AiChatMessage.Assistant>().filter { it.isPending }.forEach { answer ->
                launch {
                    aiChatRepository.followAnswer(chatId, answer.id, account).collect { event ->
                        updateAnswer(answer.id) { message ->
                            when (event) {
                                is AiAnswerEvent.Snapshot -> message.copy(content = event.content)
                                is AiAnswerEvent.Content -> message.copy(content = message.content + event.content)
                                AiAnswerEvent.Done -> message.copy(isPending = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateAnswer(messageId: Uuid, update: (AiChatMessage.Assistant) -> AiChatMessage.Assistant) {
        state.update { state ->
            state.copy(messages = state.messages.map { message ->
                if (message is AiChatMessage.Assistant && message.id == messageId) update(message) else message
            })
        }
    }
}

data class AgentState(
    val account: OvermailAccount? = null,
    val chats: List<AiChat> = emptyList(),
    /** Null for a new chat, which the server creates with its first question. */
    val currentChatId: Uuid? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val input: String = "",
    /** The question is on its way; the answer does not exist yet. */
    val isSending: Boolean = false,
    val error: AgentError? = null,
    /**
     * A question on its way to the server: shown as asked before the server has the chat, so
     * whatever was on screen -- the welcome, the chat before -- gives way to it at once.
     */
    val pendingQuestion: String? = null,
    val references: AgentReferences = AgentReferences(),
) {
    val currentChat: AiChat? = chats.firstOrNull { it.id == currentChatId }

    /** From sending until the answer is written; the prompt waits that long. */
    val isAnswering: Boolean = isSending || pendingQuestion != null ||
        messages.any { it is AiChatMessage.Assistant && it.isPending }

    /** [messages], and the [pendingQuestion] with an answer that has not begun yet. */
    val shownMessages: List<AiChatMessage> = if (pendingQuestion == null) messages else messages + listOf(
        AiChatMessage.User(id = PENDING_QUESTION_ID, createdAt = Clock.System.now(), text = pendingQuestion),
        AiChatMessage.Assistant(id = PENDING_ANSWER_ID, createdAt = Clock.System.now(), content = "", isPending = true),
    )

    /** Nothing asked in this chat yet, and nothing on its way: the welcome is shown. */
    val isEmpty: Boolean = currentChatId == null && messages.isEmpty() && pendingQuestion == null
}

/** The labels and mails the tool calls of the open chat name, by id; one not found is left out. */
data class AgentReferences(
    val labels: Map<Uuid, LabelReference> = emptyMap(),
    val emails: Map<Uuid, Email> = emptyMap(),
    val persons: Map<Uuid, Participant> = emptyMap(),
)

private val PENDING_QUESTION_ID = Uuid.fromLongs(0, 1)
private val PENDING_ANSWER_ID = Uuid.fromLongs(0, 2)

enum class AgentError { Load, Send, Retry }

sealed class AgentEvent {
    data class SetInput(val input: String) : AgentEvent()
    data object Send : AgentEvent()
    data object NewChat : AgentEvent()
    data class SelectChat(val chatId: Uuid) : AgentEvent()
    /** The answer [messageId], written anew. */
    data class Retry(val messageId: Uuid) : AgentEvent()
    /** [text] as the first question of a new chat. */
    data class Ask(val text: String) : AgentEvent()
}
