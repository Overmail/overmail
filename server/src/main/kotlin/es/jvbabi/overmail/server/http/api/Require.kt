package es.jvbabi.overmail.server.http.api

suspend inline fun requireThat(value: Boolean, crossinline otherwise: suspend () -> Unit) {
    if (value) return
    otherwise()
}