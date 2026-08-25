package es.jvbabi.overmail.server.domain.spam

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SpamRuleMatcherTest {

    private val matcher = SpamRuleMatcher()

    private val mail = MailFacts(
        subject = "Ihr Gewinn wartet",
        senderName = "Lotto Zentrale",
        senderAddress = "no-reply@lotto.example",
        body = "Klicken Sie hier, um Ihren Gewinn abzuholen.",
    )

    private fun match(field: SpamRuleField, match: SpamRuleMatch, value: String) =
        SpamRule.Match(field, match, value)

    @Test
    fun `contains ignores case`() {
        assertTrue(matcher.matches(match(SpamRuleField.SUBJECT, SpamRuleMatch.CONTAINS, "gewinn"), mail))
        assertFalse(matcher.matches(match(SpamRuleField.SUBJECT, SpamRuleMatch.CONTAINS, "Rechnung"), mail))
    }

    @Test
    fun `equals is the whole field, not a part of it`() {
        assertTrue(matcher.matches(match(SpamRuleField.SENDER_NAME, SpamRuleMatch.EQUALS, "lotto zentrale"), mail))
        assertFalse(matcher.matches(match(SpamRuleField.SENDER_NAME, SpamRuleMatch.EQUALS, "Lotto"), mail))
    }

    @Test
    fun `a regex is looked for anywhere in the field`() {
        assertTrue(matcher.matches(match(SpamRuleField.SENDER_ADDRESS, SpamRuleMatch.REGEX, "@lotto\\."), mail))
        assertTrue(matcher.matches(match(SpamRuleField.SENDER_ADDRESS, SpamRuleMatch.REGEX, ".*\\.example$"), mail))
        assertFalse(matcher.matches(match(SpamRuleField.SENDER_ADDRESS, SpamRuleMatch.REGEX, "^lotto"), mail))
    }

    @Test
    fun `a regex nothing can compile is not a clean mail`() {
        assertFailsWith<IllegalArgumentException> {
            matcher.matches(match(SpamRuleField.SUBJECT, SpamRuleMatch.REGEX, "([")  , mail)
        }
    }

    @Test
    fun `and needs every operand, or needs one, not turns its operand around`() {
        val hits = match(SpamRuleField.SUBJECT, SpamRuleMatch.CONTAINS, "Gewinn")
        val misses = match(SpamRuleField.SUBJECT, SpamRuleMatch.CONTAINS, "Rechnung")

        assertTrue(matcher.matches(SpamRule.And(listOf(hits, hits)), mail))
        assertFalse(matcher.matches(SpamRule.And(listOf(hits, misses)), mail))
        assertTrue(matcher.matches(SpamRule.Or(listOf(misses, hits)), mail))
        assertFalse(matcher.matches(SpamRule.Or(listOf(misses, misses)), mail))
        assertTrue(matcher.matches(SpamRule.Not(misses), mail))
        assertFalse(matcher.matches(SpamRule.Not(hits), mail))
    }

    @Test
    fun `an operator over nothing catches nothing`() {
        assertFalse(matcher.matches(SpamRule.And(emptyList()), mail))
        assertFalse(matcher.matches(SpamRule.Or(emptyList()), mail))
        // Which also means an empty group cannot be turned into a filter that catches everything.
        assertTrue(matcher.matches(SpamRule.Not(SpamRule.And(emptyList())), mail))
    }

    @Test
    fun `a mail without a display name has an empty one`() {
        val nameless = mail.copy(senderName = null)

        assertFalse(matcher.matches(match(SpamRuleField.SENDER_NAME, SpamRuleMatch.CONTAINS, "Lotto"), nameless))
        assertTrue(
            matcher.matches(
                SpamRule.Not(match(SpamRuleField.SENDER_NAME, SpamRuleMatch.CONTAINS, "Lotto")),
                nameless,
            )
        )
    }

    @Test
    fun `the body is the text part when the mail carried one`() {
        val email = email(text = "Ihr Gewinn", html = "<p>Rechnung</p>")

        assertEquals("Ihr Gewinn", email.toRuleFacts().body)
    }

    @Test
    fun `the body is the flattened html when the mail carried no text`() {
        val email = email(
            text = null,
            html = """
                <html><head><style>p { color: red }</style></head>
                <body><p>Ihr&nbsp;Gewinn</p><div>Jetzt   abholen</div></body></html>
            """.trimIndent(),
        )

        assertEquals("Ihr Gewinn\nJetzt abholen", email.toRuleFacts().body)
        // And a rule reads what the reader sees, not the markup around it.
        assertFalse(matcher.matches(match(SpamRuleField.BODY, SpamRuleMatch.CONTAINS, "style"), email.toRuleFacts()))
        assertTrue(matcher.matches(match(SpamRuleField.BODY, SpamRuleMatch.CONTAINS, "Ihr Gewinn"), email.toRuleFacts()))
    }

    @Test
    fun `a mail with neither part has an empty body`() {
        assertEquals("", email(text = null, html = null).toRuleFacts().body)
        assertEquals("", email(text = "   ", html = null).toRuleFacts().body)
    }

    @Test
    fun `the shape the editor sends is the shape this reads`() {
        // Verbatim what web/src/lib/app/spam_dialog/rule.ts builds: `op` as the tag, snake_case
        // fields and comparisons. This is the contract between the two halves.
        val rule = Json.decodeFromString<SpamRule>(
            """
            {"op":"and","operands":[
              {"op":"match","field":"subject","match":"contains","value":"gewinn"},
              {"op":"not","operand":{"op":"or","operands":[
                {"op":"match","field":"sender_address","match":"regex","value":".*@bank\\.de$"},
                {"op":"match","field":"body","match":"equals","value":"nichts"}
              ]}}
            ]}
            """.trimIndent()
        )

        assertEquals(
            SpamRule.And(
                listOf(
                    SpamRule.Match(SpamRuleField.SUBJECT, SpamRuleMatch.CONTAINS, "gewinn"),
                    SpamRule.Not(
                        SpamRule.Or(
                            listOf(
                                SpamRule.Match(SpamRuleField.SENDER_ADDRESS, SpamRuleMatch.REGEX, ".*@bank\\.de$"),
                                SpamRule.Match(SpamRuleField.BODY, SpamRuleMatch.EQUALS, "nichts"),
                            )
                        )
                    ),
                )
            ),
            rule,
        )
        assertTrue(matcher.matches(rule, mail))
    }

    private fun email(text: String?, html: String?): Email {
        val user = User(id = Uuid.random(), username = "julius", email = "julius@example", name = "Julius")

        return Email(
            id = Uuid.random(),
            imapAccount = ImapAccount(
                id = Uuid.random(),
                user = user,
                host = "imap.example",
                port = 993,
                username = "julius",
                password = "",
            ),
            sender = EmailUser(id = Uuid.random(), user = user, address = mail.senderAddress),
            senderName = mail.senderName,
            subject = mail.subject,
            sent = Instant.fromEpochSeconds(0),
            textContent = text,
            htmlContent = html,
            isRead = false,
            isArchived = false,
            recipients = emptyList(),
        )
    }
}
