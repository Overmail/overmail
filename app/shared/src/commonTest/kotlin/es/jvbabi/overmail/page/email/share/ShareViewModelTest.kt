@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.email.share

import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.domain.model.ShareDraft
import es.jvbabi.overmail.domain.repository.SharesRepository
import es.jvbabi.overmail.page.email.shareWebUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.fromEpochSeconds(1_800_000_000)

private val FIXED_CLOCK = object : Clock {
    override fun now() = NOW
}

class ShareFormTest {
    @Test
    fun presetsRunFromNow() {
        assertEquals(NOW + 7.days, ShareForm().validUntil(NOW, TimeZone.UTC))
        assertEquals(NOW + 1.days, ShareForm(expiry = ShareExpiry.OneDay).validUntil(NOW, TimeZone.UTC))
        assertNull(ShareForm(expiry = ShareExpiry.Never).validUntil(NOW, TimeZone.UTC))
    }

    @Test
    fun aPickedDayLastsToItsEnd() {
        val form = ShareForm(expiry = ShareExpiry.Custom, expiresOn = LocalDate(2030, 1, 14))
        assertEquals(Instant.parse("2030-01-14T23:59:59Z"), form.validUntil(NOW, TimeZone.UTC))
    }

    @Test
    fun problemsAreFound() {
        assertEquals(ShareFormProblem.PasswordTooShort, ShareForm(password = "abc").validated(NOW, TimeZone.UTC).problem)
        assertEquals(ShareFormProblem.ExpiryMissing, ShareForm(expiry = ShareExpiry.Custom).validated(NOW, TimeZone.UTC).problem)
        assertEquals(
            ShareFormProblem.ExpiryPast,
            ShareForm(expiry = ShareExpiry.Custom, expiresOn = LocalDate(2020, 1, 1)).validated(NOW, TimeZone.UTC).problem,
        )
        assertNull(ShareForm(password = "abcd").validated(NOW, TimeZone.UTC).problem)
    }

    @Test
    fun anUntouchedPasswordIsKeptAndRemovingNeedsAnEmptyField() {
        val share = share(hasPassword = true)
        val kept = ShareForm.of(share, TimeZone.UTC).toDraft(NOW, TimeZone.UTC)
        assertNull(kept.password)
        assertFalse(kept.removePassword)

        val removed = ShareForm.of(share, TimeZone.UTC).copy(removePassword = true).toDraft(NOW, TimeZone.UTC)
        assertTrue(removed.removePassword)

        val replaced = ShareForm.of(share, TimeZone.UTC).copy(removePassword = true, password = "secret").toDraft(NOW, TimeZone.UTC)
        assertEquals("secret", replaced.password)
        assertFalse(replaced.removePassword)
    }

    @Test
    fun theLinkOnlyCarriesTheSubjectWhereAVisitorSeesIt() {
        val open = share(hasPassword = false)
        assertTrue(shareWebUrl("https://mail.example/", open, "Hello world").startsWith("https://mail.example/share/Hello-world-"))

        val locked = share(hasPassword = true, allowMetadata = false)
        assertEquals("https://mail.example/share/${locked.id.toString().replace("-", "")}", shareWebUrl("https://mail.example", locked, "Hello"))
    }
}

class ShareViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theListIsReadWithThePageAndReloadedBehindItWhenTheSheetOpens() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val viewModel = viewModel(repository, mutableListOf())
        advanceUntilIdle()

        // Read before the sheet was ever opened: an empty list, but a known one.
        assertEquals(ShareListState.Ready, viewModel.state.value.listState)

        viewModel.onEvent(ShareEvent.Open)
        assertEquals(ShareListState.Ready, viewModel.state.value.listState)
        advanceUntilIdle()
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun creatingMakesADefaultShareHandsItOnAndOpensItsSettings() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val copied = mutableListOf<String>()
        val viewModel = viewModel(repository, copied)

        viewModel.onEvent(ShareEvent.Open)
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()

        val draft = repository.created.single()
        assertEquals(NOW + 7.days, draft.validUntil)
        assertNull(draft.password)
        assertTrue(draft.includeLabels && draft.includeAttachments)

        val state = viewModel.state.value
        val share = repository.shares.single()
        assertEquals(share, state.linkToShare)
        assertEquals(share, state.editForm?.editing)
        assertEquals(listOf(share), state.shares)

        viewModel.onEvent(ShareEvent.LinkShared)
        assertNull(viewModel.state.value.linkToShare)
    }

    @Test
    fun savingSettingsWritesToTheSameShareAndGoesBack() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val viewModel = viewModel(repository, mutableListOf())
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()

        viewModel.onEvent(ShareEvent.SetName("Team"))
        viewModel.onEvent(ShareEvent.SetPassword("secret"))
        viewModel.onEvent(ShareEvent.Save)
        advanceUntilIdle()

        val (id, draft) = repository.updated.single()
        assertEquals(repository.shares.single().id, id)
        assertEquals("Team", draft.name)
        assertEquals("secret", draft.password)
        assertNull(viewModel.state.value.editForm)
        assertEquals("Team", viewModel.state.value.shares.single().name)
    }

    @Test
    fun aTooShortPasswordIsNotSent() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val viewModel = viewModel(repository, mutableListOf())
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()

        viewModel.onEvent(ShareEvent.SetPassword("abc"))
        viewModel.onEvent(ShareEvent.Save)
        advanceUntilIdle()

        assertTrue(repository.updated.isEmpty())
        assertEquals(ShareFormProblem.PasswordTooShort, viewModel.state.value.editForm?.problem)
    }

    @Test
    fun copyingPutsTheLinkOnTheClipboardForAMoment() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val copied = mutableListOf<String>()
        val viewModel = viewModel(repository, copied)
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()
        val share = repository.shares.single()

        viewModel.onEvent(ShareEvent.Copy(share))
        assertEquals(viewModel.urlOf(share), copied.single())
        assertEquals(share.id, viewModel.state.value.copiedShareId)

        advanceUntilIdle()
        assertNull(viewModel.state.value.copiedShareId)
    }

    @Test
    fun deletingTakesTheShareAwayAndClosesItsSettings() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val viewModel = viewModel(repository, mutableListOf())
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()
        val share = repository.shares.single()

        viewModel.onEvent(ShareEvent.AskToDelete(share))
        viewModel.onEvent(ShareEvent.ConfirmDelete)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.shares.isEmpty())
        assertNull(state.editForm)
        assertNull(state.deleting)
    }

    @Test
    fun aFailedDeleteKeepsTheConfirmationOpen() = runTest(dispatcher) {
        val repository = FakeSharesRepository()
        val viewModel = viewModel(repository, mutableListOf())
        viewModel.onEvent(ShareEvent.Create)
        advanceUntilIdle()
        repository.failDeletes = true

        viewModel.onEvent(ShareEvent.AskToDelete(repository.shares.single()))
        viewModel.onEvent(ShareEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(ShareDeleteState.Failed, viewModel.state.value.deleteState)
        assertEquals(1, viewModel.state.value.shares.size)
    }

    private fun viewModel(repository: SharesRepository, copied: MutableList<String>) = ShareViewModel(
        email = EMAIL,
        sharesRepository = repository,
        clock = FIXED_CLOCK,
        timeZone = { TimeZone.UTC },
        copyToClipboard = { copied += it },
    )
}

private class FakeSharesRepository : SharesRepository {
    val shares = mutableListOf<Share>()
    val created = mutableListOf<ShareDraft>()
    val updated = mutableListOf<Pair<Uuid, ShareDraft>>()
    var failDeletes = false
    var listCalls = 0

    override suspend fun list(emailId: Uuid, account: OvermailAccount): Result<List<Share>> {
        listCalls++
        return Result.success(shares.toList())
    }

    override suspend fun create(emailId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share> {
        created += draft
        return Result.success(draft.toShare(Uuid.random()).also { shares.add(0, it) })
    }

    override suspend fun update(emailId: Uuid, shareId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share> {
        updated += shareId to draft
        val share = draft.toShare(shareId)
        shares.replaceAll { if (it.id == shareId) share else it }
        return Result.success(share)
    }

    override suspend fun delete(emailId: Uuid, shareId: Uuid, account: OvermailAccount): Result<Unit> {
        if (failDeletes) return Result.failure(IllegalStateException("offline"))
        shares.removeAll { it.id == shareId }
        return Result.success(Unit)
    }

    private fun ShareDraft.toShare(id: Uuid) = Share(
        id = id,
        name = name,
        sharedAt = NOW,
        validUntil = validUntil,
        includeLabels = includeLabels,
        includeAttachments = includeAttachments,
        hasPassword = password != null,
        allowMetadataWithoutPassword = allowMetadataWithoutPassword,
    )
}

private fun share(hasPassword: Boolean, allowMetadata: Boolean = true) = Share(
    id = Uuid.random(),
    name = null,
    sharedAt = NOW,
    validUntil = null,
    includeLabels = true,
    includeAttachments = true,
    hasPassword = hasPassword,
    allowMetadataWithoutPassword = allowMetadata,
)

private val ACCOUNT = OvermailAccount(
    id = Uuid.random(),
    username = "ada",
    firstName = "Ada",
    lastName = "Lovelace",
    email = "ada@example.com",
    homeserver = "https://mail.example",
    token = "token",
)

private val EMAIL = Email(
    id = Uuid.random(),
    overmailAccount = ACCOUNT,
    imapAccount = ImapAccount(Uuid.random(), null, "imap.example", 993, "ada", false, 0, ACCOUNT),
    sentBy = Participant(Uuid.random(), "Bob", "bob@example.com", null, null, 0, ACCOUNT),
    sentAt = NOW,
    subject = "Numbers",
    preview = null,
    hasText = true,
    hasHtml = false,
    isRead = true,
    archivedState = ArchivedState.Unarchive,
    labels = emptyList(),
    recipients = emptyList(),
    attachments = emptyList(),
)
