package com.guanyi.mirra.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.data.local.MirraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationThreeToFourTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MirraDatabase::class.java,
    )

    @Test
    fun migrationPreservesVersionThreeDataAndAddsEmptyFocusFacts() {
        val name = "migration-3-4"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO learning_items VALUES ('book','迁移书','IN_PROGRESS',200,40,1,'翻到第40页',1,2,NULL)")
            execSQL("INSERT INTO study_intents VALUES ('intent','book',3,4,5,5,'CONVERTED',NULL)")
            execSQL("INSERT INTO study_sessions VALUES ('session','book','intent',5,NULL,10,40,45,45,'NORMAL','总结',NULL)")
            execSQL("INSERT INTO notes VALUES ('note','book','session','UNDERSTANDING','迁移笔记',43,6,7)")
            close()
        }

        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).apply {
            query("SELECT name,currentPage FROM learning_items WHERE id='book'").use {
                it.moveToFirst(); assertEquals("迁移书", it.getString(0)); assertEquals(40, it.getInt(1))
            }
            query("SELECT content FROM notes WHERE id='note'").use {
                it.moveToFirst(); assertEquals("迁移笔记", it.getString(0))
            }
            listOf("risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events").forEach { table ->
                query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
            close()
        }
    }
}
