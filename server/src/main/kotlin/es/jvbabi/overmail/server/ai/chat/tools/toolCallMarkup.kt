package es.jvbabi.overmail.server.ai.chat.tools

/**
 * Escaping for the elements the tools write into an answer. Subjects and search queries are
 * arbitrary text and end up inside an attribute, quotes and all.
 */
internal fun escapeAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
