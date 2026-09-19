package es.jvbabi.overmail.domain.model

/**
 * What came of redeeming a [LoginCode], see `OvermailAccountRepository.redeemLoginCode`.
 *
 * The failures are kept apart because the screen says something different for each: a spent code
 * needs a new QR code, an unreachable server needs the network back.
 */
sealed interface LoginResult {

    /** The account is signed in and stored. */
    data class Success(val account: OvermailAccount) : LoginResult

    /** The server does not know this code -- mistyped, or already redeemed once. */
    data object UnknownCode : LoginResult

    /** The code was real, but its few minutes are up. */
    data object ExpiredCode : LoginResult

    /** Nothing answered: no network, or not an Overmail server at that address. */
    data object ServerUnreachable : LoginResult

    /** It answered, with something this app cannot make a session out of. */
    data object Failed : LoginResult
}
