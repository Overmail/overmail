package es.jvbabi.overmail.utils

inline fun <T> MutableCollection<T>.takeFrom(crossinline predicate: (T) -> Boolean): List<T> {
    val matching = filter(predicate)
    removeAll(matching)
    return matching
}