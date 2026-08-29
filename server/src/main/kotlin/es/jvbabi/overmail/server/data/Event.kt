package es.jvbabi.overmail.server.data

sealed class Event<ID> {
    abstract val id: ID

    data class Created<ID>(override val id: ID) : Event<ID>()
    data class Modified<ID>(override val id: ID) : Event<ID>()
    data class Deleted<ID>(override val id: ID) : Event<ID>()
}