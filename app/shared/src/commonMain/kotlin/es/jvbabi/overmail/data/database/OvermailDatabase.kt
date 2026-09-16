package es.jvbabi.overmail.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import es.jvbabi.overmail.data.database.dao.KeyValueDao
import es.jvbabi.overmail.data.database.entity.DbKeyValue

@Database(
    entities = [
        DbKeyValue::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class OvermailDatabase : RoomDatabase() {
    abstract val keyValueDao: KeyValueDao
}

@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<OvermailDatabase> {
    override fun initialize(): OvermailDatabase
}
