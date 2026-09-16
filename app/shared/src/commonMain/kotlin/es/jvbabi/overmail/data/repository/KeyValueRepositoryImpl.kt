package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.DbKeyValue
import es.jvbabi.overmail.domain.repository.Key
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class KeyValueRepositoryImpl(
    private val database: OvermailDatabase
): KeyValueRepository {
    override suspend fun <T> set(key: Key<T>, value: T) {
        database.keyValueDao.upsert(DbKeyValue(key.key, key.fromValue(value)))
    }

    override suspend fun <T> delete(key: Key<T>) {
        database.keyValueDao.delete(key.key)
    }

    override fun <T> get(key: Key<T>): Flow<T?> {
        return database.keyValueDao.getValue(key.key).map {
            it?.let(key::toValue) ?: key.defaultValue
        }
    }
}
