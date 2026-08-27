package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What [TOPIC_STEP]'s `validate` holds an answer to, which is everything the JSON schema cannot say:
 * how many tags, how long, that no two are the same, that each one is hung on words the mail really
 * writes, and that an identifier was copied rather than composed.
 *
 * No model here: the rules are a function of the answer and the mail, and that is the part worth
 * pinning down. Whether the model keeps to them is a question for the model.
 */
class TopicAnalysisTest {

    private val owner = MailParticipant("Julius Babies", "julius@example.org")

    private val mail = MailContext(
        owner = owner,
        direction = MailDirection.INCOMING,
        sender = MailParticipant("Hetzner Online GmbH", "billing@hetzner.com"),
        recipients = listOf(owner),
        subject = "Ihre Rechnung R0012345678",
        body = "Rechnungsnummer: R0012345678\nKundennummer: K4711008\nBetrag: 23,80 EUR\n" +
            "Zahlbar bis zum 30.09.2026 fuer Ihren Server\nCX22 im Rechenzentrum Nuernberg.",
    )

    private fun complaint(analysis: TopicAnalysis): String? = TOPIC_STEP.validate(analysis, mail)

    /** A tag off the invoice line, which is the one the mail leads with. */
    private fun invoiceTag(
        tag: String = "Rechnung",
        quote: String = "Rechnungsnummer: R0012345678",
        reason: String = "Nennt eine Rechnung mit Nummer und Betrag.",
    ) = TopicTag(quote = quote, tag = tag, reason = reason)

    @Test
    fun `tags the mail supports pass`() {
        assertNull(
            complaint(
                TopicAnalysis(
                    tags = listOf(
                        invoiceTag(),
                        invoiceTag(
                            tag = "Hosting",
                            quote = "fuer Ihren Server",
                            reason = "Es geht um einen gemieteten Server.",
                        ),
                    ),
                    threadId = "R0012345678",
                    threadKind = ThreadKind.INVOICE,
                )
            )
        )
    }

    @Test
    fun `no tags and no identifier is a complete answer`() {
        assertNull(complaint(TopicAnalysis()))
    }

    @Test
    fun `a quote across a line break is still the mail's own`() {
        // The mail's line breaks are its sender's mail program, not its words: a quote that ran
        // over one of them has still quoted the mail.
        assertNull(
            complaint(
                TopicAnalysis(
                    tags = listOf(
                        invoiceTag(quote = "Zahlbar bis zum 30.09.2026 fuer Ihren Server CX22")
                    )
                )
            )
        )
    }

    @Test
    fun `a tag read off the sender counts as read off the mail`() {
        assertNull(
            complaint(
                TopicAnalysis(
                    tags = listOf(
                        invoiceTag(
                            tag = "Hosting",
                            quote = "billing@hetzner.com",
                            reason = "Kommt von der Rechnungsstelle eines Hosters.",
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `a tag hung on words the mail never wrote is refused`() {
        // The guard that matters: a label whose evidence is nowhere in the mail was made up, and a
        // mailbox full of those is worse than one with no tags at all.
        assertNotNull(
            complaint(TopicAnalysis(tags = listOf(invoiceTag(quote = "Mahnung wegen Zahlungsverzug"))))
        )
    }

    @Test
    fun `a tag without evidence is refused`() {
        assertNotNull(complaint(TopicAnalysis(tags = listOf(invoiceTag(quote = " ")))))
    }

    @Test
    fun `a tag without a reason is refused`() {
        assertNotNull(complaint(TopicAnalysis(tags = listOf(invoiceTag(reason = " ")))))
    }

    @Test
    fun `a quote that is the whole mail again is refused`() {
        assertNotNull(
            complaint(TopicAnalysis(tags = listOf(invoiceTag(quote = mail.body + mail.body))))
        )
    }

    @Test
    fun `a reason that is a paragraph is refused`() {
        assertNotNull(
            complaint(TopicAnalysis(tags = listOf(invoiceTag(reason = "Weil ".repeat(60)))))
        )
    }

    @Test
    fun `a blank tag is not a tag`() {
        assertNotNull(complaint(TopicAnalysis(tags = listOf(invoiceTag(tag = " ")))))
    }

    @Test
    fun `more tags than a mail is worth is refused`() {
        assertNotNull(
            complaint(
                TopicAnalysis(
                    tags = listOf("Rechnung", "Hosting", "Beleg", "Zahlung", "Server")
                        .map { invoiceTag(tag = it) }
                )
            )
        )
    }

    @Test
    fun `the same tag twice is refused whatever the case`() {
        assertNotNull(
            complaint(
                TopicAnalysis(tags = listOf(invoiceTag(tag = "Rechnung"), invoiceTag(tag = "rechnung")))
            )
        )
    }

    @Test
    fun `a tag that is a sentence is refused`() {
        assertNotNull(
            complaint(TopicAnalysis(tags = listOf(invoiceTag(tag = "Rechnung von einem Hoster"))))
        )
    }

    @Test
    fun `a matter tag naming the matter it belongs to passes`() {
        // Two words, one of them a name: "Bewerbung Musterfirma" is what a reader looks under, and
        // the rule is only that the name may not stand on its own.
        assertNull(
            complaint(
                TopicAnalysis(
                    tags = listOf(
                        invoiceTag(
                            tag = "Hosting Hetzner",
                            quote = "Rechnungsnummer: R0012345678",
                            reason = "Laufende Rechnung fuer den Server bei Hetzner.",
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `an identifier without its kind is refused`() {
        assertNotNull(complaint(TopicAnalysis(threadId = "R0012345678")))
    }

    @Test
    fun `a kind without an identifier is refused`() {
        assertNotNull(complaint(TopicAnalysis(threadKind = ThreadKind.INVOICE)))
    }

    @Test
    fun `an identifier too short to identify anything is refused`() {
        assertNotNull(complaint(TopicAnalysis(threadId = "12", threadKind = ThreadKind.OTHER)))
    }

    @Test
    fun `an identifier the mail never wrote is refused`() {
        // The guard that matters: a number nobody can match later is worse than none at all.
        assertNotNull(
            complaint(TopicAnalysis(threadId = "R0099999999", threadKind = ThreadKind.INVOICE))
        )
    }

    @Test
    fun `an identifier out of the subject counts as written`() {
        assertNull(
            complaint(
                TopicAnalysis(threadId = "Ihre Rechnung R0012345678", threadKind = ThreadKind.INVOICE)
            )
        )
    }

    @Test
    fun `case is not part of an identifier`() {
        assertNull(
            complaint(TopicAnalysis(threadId = "r0012345678", threadKind = ThreadKind.INVOICE))
        )
    }
}
