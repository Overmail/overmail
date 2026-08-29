package es.jvbabi.overmail.server.auth

import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.session.SessionKey
import es.jvbabi.authentikt.core.step.BaseState
import es.jvbabi.authentikt.core.step.plugins.BasePlugin
import es.jvbabi.authentikt.core.utils.buildGenericMap
import es.jvbabi.authentikt.core.utils.respondGson
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
// Collides with authentikt's own Session, which this file is full of.
import jakarta.mail.Session as MailSession

const val EMAIL_VERIFICATION_NAMESPACE = "overmail/email-verification"

private const val CODE_LENGTH = 6
private const val UTF_8 = "UTF-8"

/**
 * Mails a one-time code to the identified user and waits for it to come back.
 *
 * Codes live in memory only: they are worthless a minute later, and a restart invalidating every
 * pending sign-in is the safe direction to fail in.
 */
class EmailVerificationPlugin(
    private val smtpConfig: SmtpConfig,
) : BasePlugin<User, EmailVerificationState>(namespace = EMAIL_VERIFICATION_NAMESPACE) {

    private val codesBySession = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    /**
     * Sessions are immutable and cheap to share; built lazily so a broken SMTP block only breaks
     * the code mail, not application startup.
     */
    private val smtpSession: MailSession by lazy {
        val properties = Properties().apply {
            put("mail.smtp.host", smtpConfig.host)
            put("mail.smtp.port", smtpConfig.port.toString())
            put("mail.smtp.auth", "true")
            put(if (smtpConfig.secure) "mail.smtp.ssl.enable" else "mail.smtp.starttls.enable", "true")
        }

        MailSession.getInstance(
            properties,
            object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(smtpConfig.auth.username, smtpConfig.auth.password)
            },
        )
    }

    override suspend fun createState(session: Session<*>): EmailVerificationState {
        val email = session.identifiedUser!!.getEmail()!!
        val code = (1..CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        codesBySession[session.sessionId] = code

        // Logged on purpose: the code has to be reachable even when the mail cannot be delivered.
        logger.info("Sign-in code for $email: $code")

        runCatching {
            mail(
                to = email,
                subject = "Your Overmail sign-in code",
                text = "Your sign-in code is $code. It only works for this sign-in attempt.",
            )
        }.onFailure { logger.warn("Could not mail the sign-in code, use the one logged above", it) }

        return EmailVerificationState(email)
    }

    /** Hands the mail to the configured SMTP server; delivery beyond that is not observable here. */
    private suspend fun mail(to: String, subject: String, text: String) {
        val message = MimeMessage(smtpSession).apply {
            setFrom(InternetAddress(smtpConfig.auth.username))
            addRecipient(Message.RecipientType.TO, InternetAddress(to))
            setSubject(subject, UTF_8)
            setText(text, UTF_8)
        }

        // Transport.send talks to the server on the calling thread.
        withContext(Dispatchers.IO) { Transport.send(message) }
    }

    override fun installRoutes(inRoute: Route, authentiktInstance: AuthentiktInstance<User>) {
        with(inRoute) {
            post("/verify") {
                val request = call.receive<VerificationRequest>()
                val session = call.attributes[SessionKey]
                val expected = codesBySession[session.sessionId]

                if (expected == null || !expected.matches(request.code)) {
                    call.respondGson(buildGenericMap { put("type", "invalid_code") })
                    return@post
                }

                // One code, one attempt: it must not survive to be replayed.
                codesBySession.remove(session.sessionId)

                val state = session.authenticationSteps.last().second as EmailVerificationState
                state.isVerified = true
                session.nextStep()

                call.respondGson(buildGenericMap { put("type", "success") })
            }
        }
    }
}

/** Constant time, so the response time cannot be used to guess the code digit by digit. */
private fun String.matches(candidate: String): Boolean =
    MessageDigest.isEqual(toByteArray(), candidate.trim().toByteArray())

class EmailVerificationState(
    private val email: String,
    var isVerified: Boolean = false,
) : BaseState {
    override suspend fun isCompleted(): Boolean = isVerified

    override suspend fun createClientState(session: Session<*>): Map<String, Any?> = buildGenericMap {
        put("email", email.masked())
    }
}

/** `someone@example.com` becomes `s*****e@example.com`, enough to recognise, not enough to leak. */
private fun String.masked(): String {
    val local = substringBefore('@')
    val domain = substringAfter('@', missingDelimiterValue = "")
    if (domain.isEmpty() || local.length < 2) return this
    return "${local.first()}${"*".repeat(local.length - 2).ifEmpty { "" }}${local.last()}@$domain"
}

@Serializable
data class VerificationRequest(
    @SerialName("code") val code: String,
)
