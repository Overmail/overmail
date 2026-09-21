package es.jvbabi.overmail.utils

/**
 * The server's `fuzzyContains` (`server/util/fuzzy.kt`): whether every character of [other]
 * appears in the receiver in that order, case-insensitive and with umlauts spelled out. The local
 * cache has to match the way the server does, or it filters out what the server just found.
 */
infix fun String.fuzzyContains(other: String): Boolean {
    val haystack = normalizeForSearch()
    var index = 0
    for (char in other.normalizeForSearch()) {
        index = haystack.indexOf(char, index)
        if (index == -1) return false
        index++
    }
    return true
}

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
