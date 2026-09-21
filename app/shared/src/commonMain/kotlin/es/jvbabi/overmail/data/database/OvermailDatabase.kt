package es.jvbabi.overmail.data.database

import androidx.room.*
import es.jvbabi.overmail.data.database.converter.ColorConverter
import es.jvbabi.overmail.data.database.converter.InstantConverter
import es.jvbabi.overmail.data.database.converter.UuidConverter
import es.jvbabi.overmail.data.database.dao.KeyValueDao
import es.jvbabi.overmail.data.database.dao.ImapAccountsDao
import es.jvbabi.overmail.data.database.dao.LabelsDao
import es.jvbabi.overmail.data.database.dao.ParticipantsDao
import es.jvbabi.overmail.data.database.dao.OvermailAccountDao
import es.jvbabi.overmail.data.database.entity.DbKeyValue
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.database.entity.DbParticipant

@Database(
    entities = [
        DbKeyValue::class,
        DbOvermailAccount::class,
        DbLabels::class,
        DbParticipant::class,
        DbImapAccount::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(
    value = [
        UuidConverter::class,
        ColorConverter::class,
        InstantConverter::class,
    ]
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class OvermailDatabase : RoomDatabase() {
    abstract val keyValueDao: KeyValueDao
    abstract val overmailAccountDao: OvermailAccountDao
    abstract val labelsDao: LabelsDao
    abstract val participantsDao: ParticipantsDao
    abstract val imapAccountsDao: ImapAccountsDao
}

@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<OvermailDatabase> {
    override fun initialize(): OvermailDatabase
}
