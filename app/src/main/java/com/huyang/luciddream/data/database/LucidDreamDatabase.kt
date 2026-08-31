package com.huyang.luciddream.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.huyang.luciddream.data.dao.AppEventDao
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.dao.OwnerChatDao
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.dao.SafetyEventDao
import com.huyang.luciddream.data.dao.ReplyBudgetDao
import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ExternalAgentDecisionDao
import com.huyang.luciddream.data.entity.AppEventEntity
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import com.huyang.luciddream.data.entity.ExternalMessageEntity
import com.huyang.luciddream.data.entity.SafetyEventEntity
import com.huyang.luciddream.data.entity.ReplyBudgetEntity
import com.huyang.luciddream.data.entity.ContactPolicyEntity
import com.huyang.luciddream.data.entity.ExternalAgentDecisionEntity

@Database(
    entities = [
        AppEventEntity::class,
        OwnerChatMessageEntity::class,
        DelegationSessionEntity::class,
        ExternalMessageEntity::class,
        SafetyEventEntity::class,
        ReplyBudgetEntity::class,
        ContactPolicyEntity::class,
        ExternalAgentDecisionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class LucidDreamDatabase : RoomDatabase() {
    abstract fun appEventDao(): AppEventDao
    abstract fun ownerChatDao(): OwnerChatDao
    abstract fun delegationSessionDao(): DelegationSessionDao
    abstract fun externalMessageDao(): ExternalMessageDao
    abstract fun safetyEventDao(): SafetyEventDao
    abstract fun replyBudgetDao(): ReplyBudgetDao
    abstract fun contactPolicyDao(): ContactPolicyDao
    abstract fun externalAgentDecisionDao(): ExternalAgentDecisionDao
}
