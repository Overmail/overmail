package es.jvbabi.overmail.data.database.converter

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlin.time.Instant

@ProvidedTypeConverter
class InstantConverter {

    @TypeConverter
    fun fromTimestamp(value: Long): Instant = Instant.fromEpochMilliseconds(value)

    @TypeConverter
    fun toTimestamp(instant: Instant): Long = instant.toEpochMilliseconds()
}