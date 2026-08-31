package com.huyang.luciddream.di

import android.content.Context
import androidx.room.Room
import com.huyang.luciddream.data.dao.AppEventDao
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.dao.OwnerChatDao
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.dao.SafetyEventDao
import com.huyang.luciddream.data.dao.ReplyBudgetDao
import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ExternalAgentDecisionDao
import com.huyang.luciddream.data.database.DatabaseMigrations
import com.huyang.luciddream.data.database.LucidDreamDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import com.huyang.luciddream.safety.LocalRuleSafetyGateway
import com.huyang.luciddream.safety.SafetyGateway
import com.huyang.luciddream.notification.Phase6ExternalMessageHandler
import com.huyang.luciddream.notification.SafeExternalMessageHandler
import com.huyang.luciddream.agent.DelegationAgentEngine
import com.huyang.luciddream.agent.DelegationDecisionGenerator
import com.huyang.luciddream.data.repository.DelegationConversationRepository
import com.huyang.luciddream.data.repository.DelegationConversationStore
import com.huyang.luciddream.session.DeepSeekSessionSummaryGenerator
import com.huyang.luciddream.session.SessionSummaryGenerator
import com.huyang.luciddream.reply.AutoReplySettingsRepository
import com.huyang.luciddream.reply.AutoReplyState

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LucidDreamDatabase =
        Room.databaseBuilder(
            context,
            LucidDreamDatabase::class.java,
            "lucid_dream.db",
        ).addMigrations(
            DatabaseMigrations.MIGRATION_1_2,
            DatabaseMigrations.MIGRATION_2_3,
            DatabaseMigrations.MIGRATION_3_4,
            DatabaseMigrations.MIGRATION_4_5,
            DatabaseMigrations.MIGRATION_5_6,
            DatabaseMigrations.MIGRATION_6_7,
            DatabaseMigrations.MIGRATION_7_8,
        ).build()

    @Provides
    fun provideAppEventDao(database: LucidDreamDatabase): AppEventDao = database.appEventDao()

    @Provides
    fun provideOwnerChatDao(database: LucidDreamDatabase): OwnerChatDao = database.ownerChatDao()

    @Provides
    fun provideDelegationSessionDao(database: LucidDreamDatabase): DelegationSessionDao =
        database.delegationSessionDao()

    @Provides
    fun provideExternalMessageDao(database: LucidDreamDatabase): ExternalMessageDao =
        database.externalMessageDao()

    @Provides
    fun provideSafetyEventDao(database: LucidDreamDatabase): SafetyEventDao = database.safetyEventDao()

    @Provides
    fun provideReplyBudgetDao(database: LucidDreamDatabase): ReplyBudgetDao = database.replyBudgetDao()

    @Provides
    fun provideContactPolicyDao(database: LucidDreamDatabase): ContactPolicyDao = database.contactPolicyDao()

    @Provides
    fun provideExternalAgentDecisionDao(database: LucidDreamDatabase): ExternalAgentDecisionDao =
        database.externalAgentDecisionDao()

    @Provides
    @Singleton
    fun provideSafetyGateway(implementation: LocalRuleSafetyGateway): SafetyGateway = implementation

    @Provides
    @Singleton
    fun provideSafeExternalMessageHandler(
        implementation: Phase6ExternalMessageHandler,
    ): SafeExternalMessageHandler = implementation

    @Provides
    @Singleton
    fun provideDelegationDecisionGenerator(
        implementation: DelegationAgentEngine,
    ): DelegationDecisionGenerator = implementation

    @Provides
    @Singleton
    fun provideDelegationConversationStore(
        implementation: DelegationConversationRepository,
    ): DelegationConversationStore = implementation

    @Provides
    @Singleton
    fun provideSessionSummaryGenerator(
        implementation: DeepSeekSessionSummaryGenerator,
    ): SessionSummaryGenerator = implementation

    @Provides
    @Singleton
    fun provideAutoReplyState(
        implementation: AutoReplySettingsRepository,
    ): AutoReplyState = implementation

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}
