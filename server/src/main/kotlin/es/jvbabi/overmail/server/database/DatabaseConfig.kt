package es.jvbabi.overmail.server.database

data class DatabaseConfig(
    val host: String = "postgres18.werkbank.studio",
    val port: Int = 5432,
    val database: String = "overmail_overmail",
    val user: String = "werkbank",
    val password: String = "werkbank",
) {
    val r2dbcUrl: String get() = "r2dbc:postgresql://$host:$port/$database"
}
