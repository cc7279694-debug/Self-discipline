package com.guanyi.mirra.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.data.local.MirraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationOneToFourTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), MirraDatabase::class.java,
    )

    @Test
    fun allMigrationsPreservePhaseOneData() {
        val name = "migration-1-4"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO learning_items VALUES ('book','旧数据','IN_PROGRESS',120,12,1,'翻到第12页',1,2,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).apply {
            query("SELECT name,currentPage FROM learning_items WHERE id='book'").use {
                it.moveToFirst(); assertEquals("旧数据", it.getString(0)); assertEquals(12, it.getInt(1))
            }
            close()
        }
    }
}
