package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.TagUsage

/**
 * How close two tag names are, from 0.0 for nothing in common to 1.0 for the same word.
 *
 * Not a general-purpose string metric. It exists for one question -- "is the mailbox already using a
 * word for this?" -- and it is tuned for the way that goes wrong in German: a plural next to a
 * singular ("Rechnungen" against "Rechnung"), a compound against its head ("Stromrechnung" against
 * "Rechnung"), an umlaut written out ("Kündigung" against "Kuendigung"), a case that differs. Every
 * one of those is one tag written twice, and a mailbox that collects them stops being searchable.
 *
 * Three signals, and the strongest of them wins:
 *
 * - the normalised words being equal, which is where the plural and the umlaut are dealt with;
 * - one containing the other, which is where compounds are: a tag whose whole name sits inside
 *   another name is about the same thing more often than not, and it is worth putting in front of a
 *   model to decide;
 * - how far apart the two are letter by letter, which catches the typo and the near-miss that
 *   neither of the other two sees.
 *
 * What it never does is decide. It only ranks what to show, see [similarTo]: whether "Beleg" really
 * means the mailbox's "Rechnung" is a question about the mail, and the model answers that one.
 */
fun tagSimilarity(one: String, other: String): Double {
    val a = one.normalisedForComparison()
    val b = other.normalisedForComparison()

    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0

    val stemmedA = a.stemmed()
    val stemmedB = b.stemmed()
    if (stemmedA == stemmedB) return 0.95

    val contained = when {
        stemmedA.length >= MIN_CONTAINED && stemmedB.contains(stemmedA) -> true
        stemmedB.length >= MIN_CONTAINED && stemmedA.contains(stemmedB) -> true
        else -> false
    }

    // Long enough to mean something on its own: "Rechnung" inside "Stromrechnung" is a signal, "Job"
    // inside "Jobcenter" is closer to a coincidence, and three letters inside anything is noise.
    val containment = if (contained) 0.85 else 0.0

    val distance = levenshtein(a, b)
    val letters = 1.0 - distance.toDouble() / maxOf(a.length, b.length)

    return maxOf(containment, letters).coerceIn(0.0, 1.0)
}

/**
 * The tags of the mailbox that come closest to [name], the closest first, at most [limit] of them.
 *
 * What is left out is everything under [threshold], because a list of every tag the mailbox has is
 * not help -- it is the mailbox again, and a model handed all of it picks off it whether or not
 * anything fits. Ties go to the tag more of the mailbox is already under: between two equally close
 * words, the one in use is the one that keeps a mailbox consistent.
 */
fun similarTo(
    name: String,
    existing: List<TagUsage>,
    limit: Int = 5,
    threshold: Double = SIMILAR_ENOUGH,
): List<TagMatch> = existing
    .asSequence()
    .map { TagMatch(usage = it, closeness = tagSimilarity(name, it.tag.name)) }
    .filter { it.closeness >= threshold }
    .sortedWith(compareByDescending<TagMatch> { it.closeness }.thenByDescending { it.usage.mails })
    .take(limit)
    .toList()

/** One existing tag, and how close it is to the name that was looked up. */
data class TagMatch(val usage: TagUsage, val closeness: Double) {
    /** The same, in the two words a model reads it by: how close, and how used. */
    fun asLine(): String = "${usage.tag.name} (${usage.mails} Mails)"
}

/**
 * Where a name stops being another spelling of the same thing.
 *
 * Deliberately generous: what is on the other side of this line is not a decision, it is a
 * suggestion put in front of a model that can read the mail. A candidate too many costs a line in a
 * prompt; one too few costs a second tag for a thing the mailbox already had a word for.
 */
const val SIMILAR_ENOUGH = 0.6

/** Shorter than this, a substring match means nothing: "Job" sits inside plenty of words. */
private const val MIN_CONTAINED = 5

/**
 * The name as it is compared: lower case, umlauts written out, everything that is not a letter or a
 * digit gone.
 *
 * Umlauts are written out rather than stripped of their dots, because that is how the two spellings
 * actually differ in a mailbox: somebody's mail programme wrote "Kuendigung" and somebody else's
 * wrote "Kündigung", and they are the same word.
 */
private fun String.normalisedForComparison(): String = lowercase()
    .replace("ä", "ae")
    .replace("ö", "oe")
    .replace("ü", "ue")
    .replace("ß", "ss")
    .filter { it.isLetterOrDigit() }

/**
 * The word without the ending German puts on it: "rechnungen" and "rechnung" are one word, and so
 * are "termine" and "termin".
 *
 * Crude and on purpose -- a real stemmer is a dependency and a whole class of surprises, and what is
 * being asked here is only whether two tags are worth showing next to each other.
 */
private fun String.stemmed(): String {
    if (length <= MIN_STEM) return this

    for (ending in ENDINGS) {
        if (endsWith(ending) && length - ending.length >= MIN_STEM) return dropLast(ending.length)
    }

    return this
}

/** Short words keep their ending: "abo" is not "ab". */
private const val MIN_STEM = 4

/**
 * The endings German inflection puts on a noun, longest first.
 *
 * Inflection only -- what makes "Rechnungen" the plural of "Rechnung" -- and nothing derivational.
 * An earlier version took "ungen" off as one ending, which cut "Rechnungen" back to "rechn" while
 * "Rechnung" stayed whole: an over-eager rule does not merely fail to match, it makes the two words
 * *less* alike than leaving them alone would have.
 */
private val ENDINGS = listOf("ern", "em", "er", "es", "en", "e", "n", "s")

/** How many single-character edits turn one string into the other. */
private fun levenshtein(one: String, other: String): Int {
    // One row at a time rather than the whole table: these are tag names, but there is no reason to
    // hold a matrix for them either.
    var previous = IntArray(other.length + 1) { it }
    var current = IntArray(other.length + 1)

    for (i in 1..one.length) {
        current[0] = i

        for (j in 1..other.length) {
            val substitution = previous[j - 1] + if (one[i - 1] == other[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
        }

        val swap = previous
        previous = current
        current = swap
    }

    return previous[other.length]
}
