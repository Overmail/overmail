package es.jvbabi.overmail.data.database.converter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter

/**
 * Stores a color as its `0xAARRGGBB`. Not [Color.value]: that is Compose's packed form, the ARGB
 * shifted into the upper half plus the color space, and `Color(Long)` reads ARGB from the lower half.
 */
@ProvidedTypeConverter
class ColorConverter {
    @TypeConverter
    fun fromLong(value: Long): Color = Color(value.toInt())

    @TypeConverter
    fun toLong(color: Color): Long = color.toArgb().toLong()
}
