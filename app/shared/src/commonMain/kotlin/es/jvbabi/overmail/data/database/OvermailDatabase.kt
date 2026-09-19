package es.jvbabi.overmail.data.database

import androidx.room.*
import es.jvbabi.overmail.data.database.converter.UuidConverter
import es.jvbabi.overmail.data.database.dao.KeyValueDao
import es.jvbabi.overmail.data.database.dao.OvermailAccountDao
import es.jvbabi.overmail.data.database.entity.DbKeyValue
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount

@Database(
    entities = [
        DbKeyValue::class,

        DbOvermailAccount::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(UuidConverter::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class OvermailDatabase : RoomDatabase() {
    abstract val keyValueDao: KeyValueDao
    abstract val overmailAccountDao: OvermailAccountDao
}

@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<OvermailDatabase> {
    override fun initialize(): OvermailDatabase
}
