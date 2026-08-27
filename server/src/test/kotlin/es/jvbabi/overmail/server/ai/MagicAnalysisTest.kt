package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [MAGIC_STEP]'s `validate` holds an answer to, which is everything the JSON schema cannot
 * say: that the flags and what they carry agree, that a code was copied rather than composed, and
 * that a link is one of the mail's own.
 *
 * No model here: the rules are a function of the answer and the mail. Whether the model keeps to
 * them is a question for the model.
 */
class MagicAnalysisTest {

    private val owner = MailParticipant("Julius Babies", "julius@example.org")

    /** A sign-in mail as a step sees one: the code written out, the links whole beside the body. */
    private val mail = MailContext(
        owner = owner,
        direction = MailDirection.INCOMING,
        sender = MailParticipant("GitHub", "noreply@github.com"),
        recipients = listOf(owner),
        subject = "Your GitHub sign-in code",
        // The links stand as hosts here, as `readableBody` leaves them.
        body = "Ihr Code: 418 902\nOder direkt anmelden: github.com\n10 Minuten gueltig.",
        links = listOf(
            "https://github.com/login/device?code=abc123",
            "https://github.com/settings/security",
        ),
    )

    /** The same mail without a single link in it, for the answer that claims one anyway. */
    private val codeOnlyMail = mail.copy(links = emptyList())

    private fun complaint(analysis: MagicAnalysis, context: MailContext = mail): String? =
        MAGIC_STEP.validate(analysis, context)

    private val readingOfTheMail = MagicAnalysis(
        carriesCode = true,
        carriesLink = true,
        provider = "GitHub",
        code = "418 902",
        linkNumber = 1,
        validForMinutes = 10,
    )

    @Test
    fun `a code and a link the mail carries pass`() {
        assertNull(complaint(readingOfTheMail))
    }

    @Test
    fun `a mail that is neither is a complete answer`() {
        assertNull(complaint(MagicAnalysis()))
    }

    @Test
    fun `a code regrouped or lowercased is still the mail's own`() {
        // "418902" for "418 902", and case is not part of a code either: the reader types it into a
        // field that does not care, and a model that dropped the space still copied it.
        assertNull(complaint(readingOfTheMail.copy(code = "418902")))
    }

    @Test
    fun `a code the mail never wrote is refused`() {
        assertNotNull(complaint(readingOfTheMail.copy(code = "999 111")))
    }

    @Test
    fun `a code that came back as the sentence around it is refused`() {
        assertNotNull(
            complaint(readingOfTheMail.copy(code = "Ihr Code lautet 418 902 und gilt zehn Minuten"))
        )
    }

    @Test
    fun `a mail said to carry a code without the code is refused`() {
        assertNotNull(complaint(readingOfTheMail.copy(code = null)))
    }

    @Test
    fun `a blank code is refused`() {
        assertNotNull(complaint(readingOfTheMail.copy(code = " ")))
    }

    @Test
    fun `a code on a mail said to carry none is refused`() {
        assertNotNull(
            complaint(
                MagicAnalysis(carriesLink = true, provider = "GitHub", code = "418 902", linkNumber = 1)
            )
        )
    }

    @Test
    fun `a mail said to carry a link without the number is refused`() {
        assertNotNull(complaint(readingOfTheMail.copy(linkNumber = null)))
    }

    @Test
    fun `a link number past the ones the mail has is refused`() {
        assertNotNull(complaint(readingOfTheMail.copy(linkNumber = 3)))
    }

    @Test
    fun `a link number that is not one is refused`() {
        // The list is numbered from 1, so a zero names nothing -- an off-by-one is not a way in.
        assertNotNull(complaint(readingOfTheMail.copy(linkNumber = 0)))
    }

    @Test
    fun `a link number on a mail said to carry no link is refused`() {
        assertNotNull(
            complaint(
                MagicAnalysis(carriesCode = true, provider = "GitHub", code = "418 902", linkNumber = 1)
            )
        )
    }

    @Test
    fun `a link claimed on a mail that carries none is refused`() {
        assertNotNull(complaint(readingOfTheMail, codeOnlyMail))
    }

    @Test
    fun `the code alone is a whole answer`() {
        assertNull(
            complaint(
                MagicAnalysis(
                    carriesCode = true,
                    provider = "GitHub",
                    code = "418 902",
                    validForMinutes = 10,
                ),
                codeOnlyMail,
            )
        )
    }
}
