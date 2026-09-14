package com.guanyi.mirra.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `image_assets` (
                `id` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `localPath` TEXT NOT NULL,
                `caption` TEXT,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `fileSize` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_image_assets_noteId` ON `image_assets` (`noteId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_image_assets_localPath` ON `image_assets` (`localPath`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `topics` (
                `id` TEXT NOT NULL,
                `name` TEXT COLLATE NOCASE NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_topics_name` ON `topics` (`name`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_topic_cross_refs` (
                `noteId` TEXT NOT NULL,
                `topicId` TEXT NOT NULL,
                PRIMARY KEY(`noteId`, `topicId`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_note_topic_cross_refs_topicId` ON `note_topic_cross_refs` (`topicId`)",
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `search_fts` USING FTS4(
                `entityType` TEXT NOT NULL,
                `entityId` TEXT NOT NULL,
                `searchableText` TEXT NOT NULL,
                `normalizedTokens` TEXT NOT NULL,
                tokenize=unicode61,
                notindexed=`entityType`,
                notindexed=`entityId`,
                notindexed=`searchableText`
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `risk_apps` (`packageName` TEXT NOT NULL, `labelSnapshot` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `session_focus_contexts` (
              `sessionId` TEXT NOT NULL, `snapshotVersion` INTEGER NOT NULL,
              `pollIntervalMillis` INTEGER NOT NULL, `riskEventDedupeMillis` INTEGER NOT NULL,
              `riskConfirmMillis` INTEGER NOT NULL, `secondFrictionMillis` INTEGER NOT NULL,
              `thirdPlusFrictionMillis` INTEGER NOT NULL, `replyAllowanceMillis` INTEGER NOT NULL,
              `researchAllowanceMillis` INTEGER NOT NULL, `temporaryTaskAllowanceMillis` INTEGER NOT NULL,
              `casualAllowanceMillis` INTEGER NOT NULL, `allowanceExtensionMillis` INTEGER NOT NULL,
              `maxAllowanceExtensions` INTEGER NOT NULL, `shortBreakMillis` INTEGER NOT NULL,
              `longBreakMillis` INTEGER NOT NULL, `recoveryStableMillis` INTEGER NOT NULL,
              `stableStartMillis` INTEGER NOT NULL, `deepFocusMillis` INTEGER NOT NULL,
              `deepFocusScreenOffMillis` INTEGER NOT NULL, `heartbeatMillis` INTEGER NOT NULL,
              `maxObservationGapMillis` INTEGER NOT NULL, `usageAccessAtStart` INTEGER NOT NULL,
              `dndAccessAtStart` INTEGER NOT NULL, `overlayAccessAtStart` INTEGER NOT NULL,
              `notificationAccessAtStart` INTEGER NOT NULL, `monitoringStatus` TEXT NOT NULL,
              `monitoringLostAt` INTEGER, `priorDndInterruptionFilter` INTEGER, `dndRuleId` TEXT,
              `dndLifecycle` TEXT NOT NULL, `closeoutState` TEXT NOT NULL, `requestedEndPage` INTEGER,
              `closeoutStartedAt` INTEGER, `lastHeartbeatAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`),
              FOREIGN KEY(`sessionId`) REFERENCES `study_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `session_risk_app_snapshots` (
              `sessionId` TEXT NOT NULL, `packageName` TEXT NOT NULL, `labelSnapshot` TEXT NOT NULL,
              PRIMARY KEY(`sessionId`, `packageName`),
              FOREIGN KEY(`sessionId`) REFERENCES `study_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `session_segments` (
              `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `type` TEXT NOT NULL,
              `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `packageName` TEXT, `reason` TEXT,
              `plannedEndAt` INTEGER, `extensionCount` INTEGER NOT NULL, `relatedSegmentId` TEXT,
              `activeSlot` INTEGER, PRIMARY KEY(`id`),
              FOREIGN KEY(`sessionId`) REFERENCES `study_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_segments_sessionId` ON `session_segments` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_segments_sessionId_startedAt` ON `session_segments` (`sessionId`, `startedAt`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_segments_activeSlot` ON `session_segments` (`activeSlot`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `focus_events` (
              `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `type` TEXT NOT NULL,
              `occurredAt` INTEGER NOT NULL, `packageName` TEXT, `segmentId` TEXT,
              `deliveryChannel` TEXT, PRIMARY KEY(`id`),
              FOREIGN KEY(`sessionId`) REFERENCES `study_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_events_sessionId_occurredAt` ON `focus_events` (`sessionId`, `occurredAt`)")
    }
}
