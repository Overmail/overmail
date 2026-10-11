package es.jvbabi.overmail.domain.usecase.housekeeping

import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.CacheableResource.Source
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import es.jvbabi.overmail.domain.repository.RedeemAuthCodeResponse
import es.jvbabi.overmail.domain.repository.UserinfoResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SyncNotificationChannelsUseCaseTest {

    private val accounts = MutableStateFlow<List<OvermailAccount>>(emptyList())
    private val channels = FakeChannels()

    private val first = account("first")
    private val second = account("second")

    /** What each account's mailboxes are, as the repository would emit them. */
    private val imapAccounts = mapOf(
        first.id to MutableStateFlow(CacheableResource(emptyList<ImapAccount>(), Source.Cache)),
        second.id to MutableStateFlow(CacheableResource(emptyList<ImapAccount>(), Source.Cache)),
    )

    @Test
    fun `the system channel is there without anybody signed in`() = runTest {
        start()

        assertEquals(1, channels.systemChannels)
        assertEquals(emptyMap(), channels.accounts)
    }

    @Test
    fun `every account gets a category with a channel per mailbox`() = runTest {
        accounts.value = listOf(first, second)
        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first), mailbox("b", first)), Source.Network)
        imapAccounts.getValue(second.id).value = CacheableResource(listOf(mailbox("c", second)), Source.Network)

        start()

        assertEquals(mapOf("first" to setOf("a", "b"), "second" to setOf("c")), channels.accounts)
    }

    @Test
    fun `a mailbox that turns up later gets its channel`() = runTest {
        accounts.value = listOf(first)
        start()
        assertEquals(mapOf("first" to emptySet()), channels.accounts)

        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first)), Source.Network)
        runCurrent()

        assertEquals(mapOf("first" to setOf("a")), channels.accounts)
    }

    @Test
    fun `a mailbox the server no longer lists loses its channel`() = runTest {
        accounts.value = listOf(first)
        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first), mailbox("b", first)), Source.Network)
        start()

        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first)), Source.Network)
        runCurrent()

        assertEquals(mapOf("first" to setOf("a")), channels.accounts)
    }

    @Test
    fun `a cache the server has not confirmed removes nothing`() = runTest {
        accounts.value = listOf(first)
        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first)), Source.Network)
        start()

        imapAccounts.getValue(first.id).value = CacheableResource(emptyList(), Source.Cache)
        runCurrent()
        imapAccounts.getValue(first.id).value = CacheableResource(emptyList(), Source.Fallback)
        runCurrent()

        assertEquals(mapOf("first" to setOf("a")), channels.accounts)
    }

    @Test
    fun `an account that is gone takes its category along`() = runTest {
        accounts.value = listOf(first, second)
        imapAccounts.getValue(first.id).value = CacheableResource(listOf(mailbox("a", first)), Source.Network)
        start()

        accounts.value = listOf(second)
        runCurrent()

        assertEquals(mapOf("second" to emptySet()), channels.accounts)
    }

    private fun TestScope.start() {
        val useCase = SyncNotificationChannelsUseCase(
            accountRepository = FakeAccountList(accounts),
            imapAccountsRepository = object : ImapAccountsRepository {
                override fun getAll(instantLocalEmission: Boolean, overmailAccount: OvermailAccount) =
                    imapAccounts.getValue(overmailAccount.id)
            },
            notificationChannelRepository = channels,
        )
        backgroundScope.launch { useCase() }
        runCurrent()
    }

    private fun account(name: String) = OvermailAccount(
        id = Uuid.random(),
        username = name,
        firstName = "Julius",
        lastName = "Babies",
        email = "$name@example.com",
        homeserver = "https://overmail.example.com",
        token = name,
    )

    private fun mailbox(name: String, account: OvermailAccount) = ImapAccount(
        id = Uuid.random(),
        host = "imap.example.com",
        port = 993,
        username = name,
        isPaused = false,
        emailCount = 0,
        overmailAccount = account,
    )
}

/** The channels as the system would hold them: the mailboxes of each account, both by name. */
private class FakeChannels : NotificationChannelRepository {
    var systemChannels = 0
    val accounts = mutableMapOf<String, Set<String>>()

    override suspend fun createSystemChannel() {
        systemChannels++
    }

    override suspend fun createAccountChannels(account: OvermailAccount, imapAccounts: List<ImapAccount>, removeOthers: Boolean) {
        val names = imapAccounts.map { it.username }.toSet()
        accounts[account.username] = if (removeOthers) names else accounts[account.username].orEmpty() + names
    }

    override suspend fun removeOtherAccounts(accounts: List<OvermailAccount>) {
        this.accounts.keys.retainAll(accounts.map { it.username }.toSet())
    }
}

private class FakeAccountList(private val all: Flow<List<OvermailAccount>>) : AccountRepository {
    override fun getAccounts(): Flow<List<OvermailAccount>> = all
    override fun getById(id: Uuid): Flow<OvermailAccount?> = all.map { accounts -> accounts.find { it.id == id } }

    override suspend fun setPushToken(account: OvermailAccount, pushToken: String): Result<Unit> = error("not used")
    override suspend fun saveAccount(account: OvermailAccount) = error("not used")
    override suspend fun redeemAuthCode(homeserver: String, code: String): Result<RedeemAuthCodeResponse> = error("not used")
    override suspend fun getUserInfo(homeserver: String, token: String): Result<UserinfoResponse> = error("not used")
}
