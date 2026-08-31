package com.huyang.luciddream.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `owner_chat_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `action` TEXT,
                    `intent` TEXT,
                    `needsOwner` INTEGER,
                    `urgency` TEXT,
                    `reason` TEXT
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `delegation_sessions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `status` TEXT NOT NULL,
                    `mode` TEXT NOT NULL,
                    `startedAt` INTEGER NOT NULL,
                    `endedAt` INTEGER,
                    `defaultReplyLimit` INTEGER NOT NULL,
                    `activeSlot` INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_delegation_sessions_activeSlot` " +
                    "ON `delegation_sessions` (`activeSlot`)",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `external_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `notificationKeyHash` TEXT NOT NULL,
                    `sourcePackage` TEXT NOT NULL,
                    `sourceApp` TEXT NOT NULL,
                    `sender` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `sourceTimestamp` INTEGER NOT NULL,
                    `receivedAt` INTEGER NOT NULL,
                    `trustLevel` TEXT NOT NULL,
                    `normalizationStatus` TEXT NOT NULL,
                    `processingStatus` TEXT NOT NULL,
                    `sessionId` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_external_messages_fingerprint` " +
                    "ON `external_messages` (`fingerprint`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_external_messages_sessionId` " +
                    "ON `external_messages` (`sessionId`)",
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `external_messages` ADD COLUMN `contactKey` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "ALTER TABLE `external_messages` ADD COLUMN `safetyStatus` TEXT NOT NULL " +
                    "DEFAULT 'NOT_EVALUATED_PHASE_4'",
            )
            db.execSQL("ALTER TABLE `external_messages` ADD COLUMN `budgetUsed` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `external_messages` ADD COLUMN `budgetLimit` INTEGER NOT NULL DEFAULT 3")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `safety_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `eventFingerprint` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `sourcePackage` TEXT NOT NULL,
                    `sourceApp` TEXT NOT NULL,
                    `sender` TEXT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `result` TEXT NOT NULL,
                    `reasonCode` TEXT NOT NULL,
                    `deepSeekStatus` TEXT NOT NULL,
                    `toolStatus` TEXT NOT NULL,
                    `taskStatus` TEXT NOT NULL,
                    `memoryStatus` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_safety_events_eventFingerprint` " +
                    "ON `safety_events` (`eventFingerprint`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_safety_events_sessionId` " +
                    "ON `safety_events` (`sessionId`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reply_budgets` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `contactKey` TEXT NOT NULL,
                    `contactDisplayName` TEXT NOT NULL,
                    `replyCount` INTEGER NOT NULL,
                    `reservedCount` INTEGER NOT NULL,
                    `replyLimit` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reply_budgets_sessionId_contactKey` " +
                    "ON `reply_budgets` (`sessionId`, `contactKey`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `contact_policies` (
                    `contactKey` TEXT NOT NULL,
                    `sourcePackage` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `isAllowlisted` INTEGER NOT NULL,
                    `replyLimit` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`contactKey`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `external_messages` ADD COLUMN `deepSeekStatus` TEXT NOT NULL " +
                    "DEFAULT 'NOT_CALLED'",
            )
            db.execSQL("ALTER TABLE `external_messages` ADD COLUMN `processedAt` INTEGER")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `external_agent_decisions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `contactKey` TEXT NOT NULL,
                    `sourceMessageId` INTEGER NOT NULL,
                    `action` TEXT NOT NULL,
                    `reply` TEXT,
                    `intent` TEXT NOT NULL,
                    `needsOwner` INTEGER NOT NULL,
                    `urgency` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_external_agent_decisions_sessionId_contactKey` " +
                    "ON `external_agent_decisions` (`sessionId`, `contactKey`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_external_agent_decisions_sourceMessageId` " +
                    "ON `external_agent_decisions` (`sourceMessageId`)",
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `delegation_sessions` ADD COLUMN `summaryStatus` TEXT NOT NULL " +
                    "DEFAULT 'NOT_STARTED'",
            )
            db.execSQL("ALTER TABLE `delegation_sessions` ADD COLUMN `summary` TEXT")
            db.execSQL("ALTER TABLE `delegation_sessions` ADD COLUMN `summaryNotificationText` TEXT")
            db.execSQL(
                "ALTER TABLE `delegation_sessions` ADD COLUMN `summaryContactCount` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `delegation_sessions` ADD COLUMN `summaryNeedsOwnerCount` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE `delegation_sessions` ADD COLUMN `summaryGeneratedAt` INTEGER")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `external_agent_decisions` ADD COLUMN `deliveryStatus` TEXT NOT NULL " +
                    "DEFAULT 'NOT_ATTEMPTED'",
            )
            db.execSQL("ALTER TABLE `external_agent_decisions` ADD COLUMN `deliveryTransport` TEXT")
            db.execSQL("ALTER TABLE `external_agent_decisions` ADD COLUMN `deliveredAt` INTEGER")
            db.execSQL("ALTER TABLE `external_agent_decisions` ADD COLUMN `deliveryError` TEXT")
        }
    }
}
