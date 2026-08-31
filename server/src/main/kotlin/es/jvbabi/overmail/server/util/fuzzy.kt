package es.jvbabi.overmail.server.util

data class FuzzyMatchResult(val matches: Boolean, val ranges: List<IntRange>) {
    companion object {
        val NO_MATCH = FuzzyMatchResult(false, emptyList())
    }
}

private fun Char.normalizeUmlauts(): String = when (this) {
    'ä' -> "ae"
    'ö' -> "oe"
    'ü' -> "ue"
    'ß' -> "ss"
    else -> toString()
}

infix fun String.fuzzyContains(other: String): Boolean = detailedFuzzyContains(other).matches

/**
 * Like [fuzzyContains], but also returns which parts of the receiver were matched.
 * The ranges are indices into the original (non-normalized) receiver string.
 */
infix fun String.detailedFuzzyContains(other: String): FuzzyMatchResult {
    // Normalize the receiver while remembering which original index each normalized char came from
    val normalizedThis = StringBuilder()
    val originalIndices = mutableListOf<Int>()
    for ((originalIndex, char) in this.withIndex()) {
        for (normalizedChar in char.lowercaseChar().normalizeUmlauts()) {
            normalizedThis.append(normalizedChar)
            originalIndices.add(originalIndex)
        }
    }

    val normalizedOther = buildString {
        for (char in other) append(char.lowercaseChar().normalizeUmlauts())
    }

    val matchedIndices = sortedSetOf<Int>()
    var index = 0
    for (char in normalizedOther) {
        index = normalizedThis.indexOf(char, index)
        if (index == -1) return FuzzyMatchResult.NO_MATCH
        matchedIndices.add(originalIndices[index])
        index++
    }

    val ranges = mutableListOf<IntRange>()
    for (matchedIndex in matchedIndices) {
        val last = ranges.lastOrNull()
        if (last != null && last.last == matchedIndex - 1) {
            ranges[ranges.lastIndex] = last.first..matchedIndex
        } else {
            ranges.add(matchedIndex..matchedIndex)
        }
    }
    return FuzzyMatchResult(true, ranges)
}
