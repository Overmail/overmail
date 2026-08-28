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
    fun `what is known about the owner stands before the mail, not in it`() {
        val message = mail.copy(
            memories = listOf("K1 · Studium · Informatik an der TU Dresden (seit 2024-10-01) (agent)"),
        ).asMessage()

        assertContains(message, "K1 · Studium · Informatik an der TU Dresden")
        // Before the body: a reader told afterwards that the sender is their landlord has already
        // read the mail wrong.
        assertTrue(
            message.indexOf("K1 ·") < message.indexOf("Ihr Code"),
            "the memories stand after the body",
        )
        // And said to be background, or a step reads it as something the mail states.
        assertContains(message, "Background only")
    }

    @Test
    fun `a mailbox that knows nothing about its owner says nothing`() {
        val message = mail.asMessage()

        assertFalse(message.contains("Background only"), message)
    }

    @Test
    fun `says nothing about links for a mail that carries none`() {
        val message = mail.asMessage()

        assertFalse(message.contains("[1]"), message)
        assertFalse(message.contains("numbered"), message)
    }
}
