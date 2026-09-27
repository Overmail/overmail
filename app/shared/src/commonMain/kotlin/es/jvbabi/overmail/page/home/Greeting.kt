package es.jvbabi.overmail.page.home

import kotlinx.datetime.LocalTime

enum class Greeting {
    Night, Morning, Day, Evening;

    companion object {
        /**
         * Which greeting fits the time on the clock: night until 5, morning until 11, day until 18,
         * evening until 23, night again after that. The same split as the web app's `greeting.ts`.
         */
        fun at(time: LocalTime): Greeting = when {
            time.hour < 5 -> Night
            time.hour < 11 -> Morning
            time.hour < 18 -> Day
            time.hour <= 22 -> Evening
            else -> Night
        }
    }
}
