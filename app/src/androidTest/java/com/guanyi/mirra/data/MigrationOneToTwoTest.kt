package com.guanyi.mirra.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MirraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationOneToTwoTest {
    private val databaseName = "migration-1-2"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MirraDatabase::class.java,
    )

    @Test
    fun migrationPreservesPhaseOneAndTwoADataAndAddsEmptyImageTable() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO learning_items VALUES ('book','迁移书','IN_PROGRESS',200,40,1,'翻到第40页',1,2,NULL)")
            execSQL("INSERT INTO study_intents VALUES ('intent','book',3,4,5,5,'CONVERTED',NULL)")
            execSQL("INSERT INTO study_sessions VALUES ('session','book','intent',5,NULL,10,40,45,45,'NORMAL','读到45页',NULL)")
            execSQL("INSERT INTO notes VALUES ('note','book','session','UNDERSTANDING','迁移笔记',43,6,7)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2).apply {
            query("SELECT name,currentPage,mainlineSlot FROM learning_items WHERE id='book'").use {
                it.moveToFirst()
                assertEquals("迁移书", it.getString(0))
                assertEquals(40, it.getInt(1))
                assertEquals(1, it.getInt(2))
            }
            query("SELECT content,pageNumber FROM notes WHERE id='note'").use {
                it.moveToFirst()
                assertEquals("迁移笔记", it.getString(0))
                assertEquals(43, it.getInt(1))
            }
            query("SELECT COUNT(*) FROM study_intents").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM study_sessions").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM image_assets").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }

        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName)
    }
}
