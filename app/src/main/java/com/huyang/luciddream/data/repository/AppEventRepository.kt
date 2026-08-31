package com.huyang.luciddream.data.repository

import com.huyang.luciddream.data.dao.AppEventDao
import com.huyang.luciddream.data.entity.AppEventEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class AppEventRepository @Inject constructor(
    private val dao: AppEventDao,
) {
    fun observeRecent(): Flow<List<AppEventEntity>> = dao.observeRecent()

    suspend fun record(category: String, message: String) {
        dao.insert(
            AppEventEntity(
                timestamp = System.currentTimeMillis(),
                category = category,
                message = message,
            ),
        )
    }
}
