package es.jvbabi.overmail.server.ai.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatNameTest {

    @Test
    fun `reasoning and tool calls are not what a chat is about`() {
        val answer = "<toolcall-thinking>Let me look at the labels.\nFirst a search.</toolcall-thinking>\n\n" +
            "<toolcall-search-emails subject=\"\" sender=\"\"></toolcall-search-emails>\n\n" +
            "Erledigt."

        assertEquals("Erledigt.", stripChatMarkup(answer))
    }

    @Test
    fun `an answer cut off inside its reasoning leaves no reasoning behind`() {
        assertEquals(
            "Ich schaue nach.",
            stripChatMarkup("Ich schaue nach.\n\n<toolcall-thinking>The user wants"),
        )
    }

    @Test
    fun `an element the user never sees keeps only what it stands for`() {
        assertEquals(
            "Neu auf und aus [label] entfernt.",
            stripChatMarkup("Neu auf <label id=\"1a935734\"></label> und aus [label:7f07442f] entfernt."),
        )
    }

    @Test
    fun `the naming call gets the exchange without its markup and with what it is for`() {
        val input = chatNamingInput(
            request = "was steht in [email:1a935734]?",
            answer = "<toolcall-thinking>read it</toolcall-thinking>\n\nDer Code ist 9XWBQ.",
        )

        assertTrue(input.contains("Message:\nwas steht in [email]?"))
        assertTrue(input.contains("Answer:\nDer Code ist 9XWBQ."))
        assertFalse(input.contains("read it"))
        // Without it the model answers the question a second time instead of naming the chat.
        assertTrue(input.trim().endsWith("Answer with the name alone."))
    }

    @Test
    fun `a name a model wrapped or introduced is unwrapped`() {
        assertEquals("Ticket für die Erstitage", cleanChatName("Titel: \"Ticket für die Erstitage\"\n"))
        assertEquals("Spotify gekündigt", cleanChatName("**Spotify gekündigt.**"))
        assertEquals("Steam-Rabatt für Iberia", cleanChatName("Steam-Rabatt für Iberia\nDas passt, weil ..."))
    }

    @Test
    fun `a name that is really an answer is cut on a word boundary`() {
        val name = cleanChatName(
            "Ich habe mir deine Studien-Mails angesehen und Folgendes gelernt."
        )

        assertEquals("Ich habe mir deine Studien-Mails angesehen und Folgendes", name)
    }

    @Test
    fun `a single word longer than the limit is cut where it is`() {
        val name = cleanChatName("D".repeat(80))

        assertEquals("D".repeat(MAX_CHAT_NAME_LENGTH), name)
    }

    @Test
    fun `a name that is only markup is no name`() {
        assertNull(cleanChatName("<toolcall-search-emails></toolcall-search-emails>"))
        assertNull(cleanChatName("   \n\"\"\n"))
    }
}
