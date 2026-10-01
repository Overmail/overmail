package es.jvbabi.overmail.utils

import kotlin.math.min

/**
 * The server's `TextSearch` (`server/util/search.kt`), word for word: the local cache has to match
 * the way the server does, or it shows mails the server would not. Every word of the query has to
 * turn up somewhere in the texts it is asked about, in any order.
 *
 * A word turns up when it is part of a word there -- "rechnung" is in "Stromrechnung" -- or, from
 * four letters on, when a word there begins with it give or take a typo: one, and two from eight
 * letters on. Not [fuzzyContains]: letters in the right order somewhere in a whole mail body is
 * nearly every mail.
 *
 * Built once per search and asked about many mails: how close a word of theirs comes to the query
 * is worked out once per word, however many mails it is in.
 */
class TextSearch(query: String) {
    private val tokens: List<String> = query.normalizeForSearch().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Per word met so far, which of [tokens] it comes close to. */
    private val closeness = HashMap<String, BooleanArray>()

    /** Whether there is anything to search for; a blank query lets every mail through. */
    val isEmpty: Boolean get() = tokens.isEmpty()

    fun matches(vararg texts: String?): Boolean {
        if (tokens.isEmpty()) return true
        val text = texts.filterNotNull().joinToString("\n").normalizeForSearch()

        // The words are only split out for a token that is not in the text as it is.
        var words: List<String>? = null
        for ((index, token) in tokens.withIndex()) {
            if (token in text) continue
            if (token.length < MIN_TYPO_LENGTH) return false
            val split = words ?: text.split(NON_WORD).filter { it.isNotEmpty() }.also { words = it }
            if (split.none { word -> closenessOf(word)[index] }) return false
        }
        return true
    }

    private fun closenessOf(word: String): BooleanArray = closeness.getOrPut(word) {
        BooleanArray(tokens.size) { index ->
            val token = tokens[index]
            token.length >= MIN_TYPO_LENGTH && word.beginsCloseTo(token, allowedTypos(token))
        }
    }
}

private val WHITESPACE = Regex("\\s+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/** Below this a typo turns a word into a different one: "mail" is one letter from "main". */
private const val MIN_TYPO_LENGTH = 4

private fun allowedTypos(token: String): Int = if (token.length >= 8) 2 else 1

/**
 * Whether the receiver begins with [token], give or take [allowed] typos -- a letter missing, one
 * too many, a wrong one or two swapped. The receiver may go on behind it: its beginning is
 * compared, as long as [token] give or take the typos.
 */
private fun String.beginsCloseTo(token: String, allowed: Int): Boolean {
    if (length < token.length - allowed) return false
    val compared = min(length, token.length + allowed)

    // Optimal string alignment distance between the token and every beginning of the word at
    // once: the last row holds the distance to each of them.
    var beforePrevious = IntArray(compared + 1)
    var previous = IntArray(compared + 1) { it }
    var current = IntArray(compared + 1)
    for (i in 1..token.length) {
        current[0] = i
        var rowMinimum = current[0]
        for (j in 1..compared) {
            val cost = if (token[i - 1] == this[j - 1]) 0 else 1
            var distance = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (i > 1 && j > 1 && token[i - 1] == this[j - 2] && token[i - 2] == this[j - 1]) {
                distance = min(distance, beforePrevious[j - 2] + 1)
            }
            current[j] = distance
            rowMinimum = min(rowMinimum, distance)
        }
        // Every beginning is already further off than allowed, and it only gets worse.
        if (rowMinimum > allowed) return false
        val recycled = beforePrevious
        beforePrevious = previous
        previous = current
        current = recycled
    }

    val shortest = (token.length - allowed).coerceAtLeast(1)
    return (shortest..compared).any { previous[it] <= allowed }
}

/** Lowercase, with umlauts spelled out, as [fuzzyContains] reads them. */
private fun String.normalizeForSearch(): String = buildString {
    for (char in this@normalizeForSearch) {
        when (val lower = char.lowercaseChar()) {
            'ä' -> append("ae")
            'ö' -> append("oe")
            'ü' -> append("ue")
            'ß' -> append("ss")
            else -> append(lower)
        }
    }
}
