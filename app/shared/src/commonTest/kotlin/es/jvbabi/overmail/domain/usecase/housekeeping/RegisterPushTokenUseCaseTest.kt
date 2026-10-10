package es.jvbabi.overmail.domain.usecase.housekeeping

import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import es.jvbabi.overmail.domain.repository.RedeemAuthCodeResponse
import es.jvbabi.overmail.domain.repository.UserinfoResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterPushTokenUseCaseTest {

    private val accounts = FakeAccounts()
    private val pushToken = MutableStateFlow<String?>(null)
    private val pushTokens = object : PushTokenRepository {
        override fun getToken(): Flow<String> = pushToken.filterNotNull()
    }

    private val first = account("first")
    private val second = account("second")

    @Test
    fun `every account gets the token`() = runTest {
        accounts.all.value = listOf(first, second)
        pushToken.value = "push-1"

        start()

        assertEquals(listOf("first" to "push-1", "second" to "push-1"), accounts.registered)
    }

    @Test
    fun `nothing is sent while there is no token`() = runTest {
        accounts.all.value = listOf(first)

        start()

        assertEquals(emptyList(), accounts.registered)
    }

    @Test
    fun `a replaced token is sent again`() = runTest {
        accounts.all.value = listOf(first)
        pushToken.value = "push-1"
        start()

        pushToken.value = "push-2"
        runCurrent()

        assertEquals(listOf("first" to "push-1", "first" to "push-2"), accounts.registered)
    }

    @Test
    fun `an account that signs in later gets the token`() = runTest {
        pushToken.value = "push-1"
        start()
        assertEquals(emptyList(), accounts.registered)

        accounts.all.value = listOf(first)
        runCurrent()

        assertEquals(listOf("first" to "push-1"), accounts.registered)
    }

    @Test
    fun `a change that is not a sign-in sends nothing`() = runTest {
        accounts.all.value = listOf(first)
        pushToken.value = "push-1"
        start()

        accounts.all.value = listOf(first.copy(firstName = "Renamed"))
        runCurrent()

        assertEquals(listOf("first" to "push-1"), accounts.registered)
    }

    @Test
    fun `signing in again is a new session and gets the token`() = runTest {
        accounts.all.value = listOf(first)
        pushToken.value = "push-1"
        start()

        accounts.all.value = listOf(first.copy(token = "first-again"))
        runCurrent()

        assertEquals(listOf("first" to "push-1", "first-again" to "push-1"), accounts.registered)
    }

    @Test
    fun `an account that cannot be reached does not keep the others from theirs`() = runTest {
        accounts.all.value = listOf(first, second)
        accounts.unreachable += "first"
        pushToken.value = "push-1"

        start()

        assertEquals(listOf("second" to "push-1"), accounts.registered)
    }

    private fun TestScope.start() {
        backgroundScope.launch { RegisterPushTokenUseCase(accounts, pushTokens)() }
        runCurrent()
    }

    private fun account(token: String) = OvermailAccount(
        id = Uuid.random(),
        username = token,
        firstName = "Julius",
        lastName = "Babies",
        email = "$token@example.com",
        homeserver = "https://overmail.example.com",
        token = token,
    )
}

private class FakeAccounts : AccountRepository {
    val all = MutableStateFlow<List<OvermailAccount>>(emptyList())

    /** Session token to push token, in the order they were sent. */
    val registered = mutableListOf<Pair<String, String>>()

    /** The session tokens of accounts whose server does not answer. */
    val unreachable = mutableSetOf<String>()

    override fun getAccounts(): Flow<List<OvermailAccount>> = all

    override suspend fun setPushToken(account: OvermailAccount, pushToken: String): Result<Unit> {
        if (account.token in unreachable) return Result.failure(IllegalStateException("unreachable"))
        registered += account.token to pushToken
        return Result.success(Unit)
    }

    override fun getById(id: Uuid): Flow<OvermailAccount?> = all.map { accounts -> accounts.find { it.id == id } }

    override suspend fun saveAccount(account: OvermailAccount) = error("not used")
    override suspend fun redeemAuthCode(homeserver: String, code: String): Result<RedeemAuthCodeResponse> = error("not used")
    override suspend fun getUserInfo(homeserver: String, token: String): Result<UserinfoResponse> = error("not used")
}
