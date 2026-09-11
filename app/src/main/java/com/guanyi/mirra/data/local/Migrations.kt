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
