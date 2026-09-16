package es.jvbabi.overmail.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "key_value",
    primaryKeys = ["key"],
    indices = [Index(value = ["key"], unique = true)]
)
data class DbKeyValue(
    @ColumnInfo("key") val key: String,
    @ColumnInfo("value") val value: String
)