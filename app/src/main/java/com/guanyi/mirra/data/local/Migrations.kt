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
