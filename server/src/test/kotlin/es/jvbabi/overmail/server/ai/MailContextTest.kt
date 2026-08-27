package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a mail reads as one message, for the part a step's answer is checked against: the numbered
 * links, which are what a sign-in link is named by rather than copied out, see [MAGIC_STEP].
 */
class MailContextTest {

    private val owner = MailParticipant("Julius Babies", "julius@example.org")

    private val mail = MailContext(
        owner = owner,
        direction = MailDirection.INCOMING,
        sender = MailParticipant("GitHub", "noreply@github.com"),
        recipients = listOf(owner),
        subject = "Your GitHub sign-in code",
        body = "Ihr Code: 418 902\nOder direkt anmelden: github.com",
    )

    @Test
    fun `numbers the links from one, after the body`() {
        val message = mail.copy(
            links = listOf("https://github.com/login/device?code=abc", "https://github.com/help"),
        ).asMessage()

        assertContains(message, "[1] https://github.com/login/device?code=abc")
        assertContains(message, "[2] https://github.com/help")
        assertTrue(
            message.indexOf("Ihr Code") < message.indexOf("[1] "),
            "the body stands before the links",
        )
    }

    @Test
    fun `says nothing about links for a mail that carries none`() {
        val message = mail.asMessage()

        assertFalse(message.contains("[1]"), message)
        assertFalse(message.contains("numbered"), message)
    }
}
