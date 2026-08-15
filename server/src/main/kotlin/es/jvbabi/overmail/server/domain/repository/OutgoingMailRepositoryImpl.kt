package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.domain.models.MailAddress
import es.jvbabi.overmail.server.domain.models.OutgoingMail
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

private const val UTF_8 = "UTF-8"

class OutgoingMailRepositoryImpl(
    private val config: SmtpConfig,
) : OutgoingMailRepository {

    /**
     * Sessions are immutable and cheap to share; built lazily so a broken SMTP block only breaks
     * sending, not application startup.
     */
    private val session: Session by lazy {
        val properties = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", "true")
            put(if (config.secure) "mail.smtp.ssl.enable" else "mail.smtp.starttls.enable", "true")
        }

        Session.getInstance(
            properties,
            object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(config.auth.username, config.auth.password)
            },
        )
    }

    override suspend fun send(mail: OutgoingMail) {
        require(mail.to.isNotEmpty()) { "An outgoing mail needs at least one recipient" }
        require(mail.textContent != null || mail.htmlContent != null) {
            "An outgoing mail needs a text or an html body"
        }

        val body = MimeMultipart("alternative").apply {
            // Clients render the last part they understand, so html has to come after text.
            mail.textContent?.let { text ->
                addBodyPart(MimeBodyPart().apply { setText(text, UTF_8) })
            }
            mail.htmlContent?.let { html ->
                addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=$UTF_8") })
            }
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.auth.username))
            mail.to.forEach { addRecipient(Message.RecipientType.TO, it.toInternetAddress()) }
            mail.cc.forEach { addRecipient(Message.RecipientType.CC, it.toInternetAddress()) }
            mail.bcc.forEach { addRecipient(Message.RecipientType.BCC, it.toInternetAddress()) }
            setSubject(mail.subject, UTF_8)
            setContent(body)
        }

        // Transport.send talks to the server on the calling thread.
        withContext(Dispatchers.IO) { Transport.send(message) }
    }
}

private fun MailAddress.toInternetAddress(): InternetAddress =
    if (name == null) InternetAddress(address) else InternetAddress(address, name, UTF_8)
