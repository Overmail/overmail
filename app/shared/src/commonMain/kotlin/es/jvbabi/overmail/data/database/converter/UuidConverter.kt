package es.jvbabi.overmail.data.database.converter

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlin.uuid.Uuid

@ProvidedTypeConverter
class UuidConverter {
    @TypeConverter
    fun toUuid(value: String): Uuid = Uuid.parse(value)

    @TypeConverter
    fun fromUuid(uuid: Uuid): String = uuid.toString()
}