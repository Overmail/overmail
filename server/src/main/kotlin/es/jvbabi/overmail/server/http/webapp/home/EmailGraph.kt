package es.jvbabi.overmail.server.http.webapp.home

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * What a year can be asked for. Below that there is nothing to see -- no mail predates the epoch --
 * and the upper end only keeps a nonsense number out of the date arithmetic.
 */
private val SUPPORTED_YEARS = 1970..9999

/** Every day of a year the caller received mail on, and how much of it. */
fun Route.emailGraph() {

    authenticate(SESSION_AUTH) {
        /**
         * The days of a year mail arrived on, with how many mails arrived on each. Days without
         * mail are left out, so the response is as long as the mailbox was busy and no longer.
         * Alongside them every year there is mail in, which is what a year picker is filled from.
         *
         * The year is a UTC year and the days are UTC days, see the repository. Without a `year`
         * parameter the current one is answered -- it may well be a year with nothing in it.
         */
        get {
            // Inside `authenticate` there is a user, or the request never got here.
            val user = call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val requested = call.parameters["year"]
            val year = when (requested) {
                null -> Clock.System.now().toLocalDateTime(TimeZone.UTC).year
                else -> requested.toIntOrNull()?.takeIf { it in SUPPORTED_YEARS }
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
            }

            // Resolved per request rather than while the routes are built: reaching for the
            // repository pulls the database provider, and starting up must not wait on that.
            val emailRepository = application.dependencies.resolve<EmailRepository>()
            val days = emailRepository.getDailyCountsForUser(user, year).first()

            call.respond(
                EmailGraphResponse(
                    year = year,
                    availableYears = emailRepository.getYearsWithMailForUser(user).first(),
                    days = days.entries.associate { (day, count) -> day.toString() to count },
                )
            )
        }
    }
}

/** How busy every day of [year] was, as `/api/webapp/home/email-graph` reports it. */
@Serializable
data class EmailGraphResponse(
    @SerialName("year") val year: Int,
    /** Every year the caller has mail in, oldest first. [year] need not be one of them. */
    @SerialName("available_years") val availableYears: List<Int>,
    /** `yyyy-mm-dd` to the mails that arrived that day; a day without mail is not in here. */
    @SerialName("days") val days: Map<String, Int>,
)
