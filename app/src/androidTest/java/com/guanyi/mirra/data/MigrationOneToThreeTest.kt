package com.guanyi.mirra.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MirraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationOneToThreeTest {
    private val databaseName = "migration-1-3"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MirraDatabase::class.java,
    )

    @Test
    fun completeMigrationChainPreservesPhaseOneData() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO learning_items VALUES ('book','旧数据','IN_PROGRESS',100,18,1,'翻到第18页',1,2,NULL)")
            execSQL("INSERT INTO study_intents VALUES ('intent','book',3,4,5,5,'CONVERTED',NULL)")
            execSQL("INSERT INTO study_sessions VALUES ('session','book','intent',5,NULL,10,18,22,22,'NORMAL','旧总结',NULL)")
            execSQL("INSERT INTO notes VALUES ('note','book','session','QUESTION','旧问题？',20,6,7)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 3, true, MIGRATION_1_2, MIGRATION_2_3).apply {
            query("SELECT name,currentPage FROM learning_items WHERE id='book'").use {
                it.moveToFirst(); assertEquals("旧数据", it.getString(0)); assertEquals(18, it.getInt(1))
            }
            query("SELECT content,semanticType FROM notes WHERE id='note'").use {
                it.moveToFirst(); assertEquals("旧问题？", it.getString(0)); assertEquals("QUESTION", it.getString(1))
            }
            query("SELECT COUNT(*) FROM image_assets").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            execSQL("INSERT INTO topics VALUES ('topic','阅读',9)")
            execSQL("INSERT INTO note_topic_cross_refs VALUES ('note','topic')")
            query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            close()
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName)
    }
}
