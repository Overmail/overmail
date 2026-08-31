package es.jvbabi.overmail.server.util

private fun String.normalizeUmlauts(): String = buildString {
    for (char in this@normalizeUmlauts) {
        when (char) {
            'ä' -> append("ae")
            'ö' -> append("oe")
            'ü' -> append("ue")
            'ß' -> append("ss")
            else -> append(char)
        }
    }
}

infix fun String.fuzzyContains(other: String): Boolean {
    val lowerThis = this.lowercase().normalizeUmlauts()
    val lowerOther = other.lowercase().normalizeUmlauts()

    var index = 0
    for (char in lowerOther) {
        index = lowerThis.indexOf(char, index)
        if (index == -1) return false
        index++
    }
    return true
}