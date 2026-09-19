package es.jvbabi.overmail.data.database.converter

import androidx.room.TypeConverter
import kotlin.uuid.Uuid

class UuidConverter {
    @TypeConverter
    fun fromUuid(uuid: Uuid): String = uuid.toString()

    @TypeConverter
    fun toUuid(value: String): Uuid = Uuid.parse(value)
}
