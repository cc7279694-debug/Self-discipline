package com.guanyi.mirra.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationFourToFiveTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MirraDatabase::class.java)

    @Test fun addingIndependentRecordTablePreservesExistingBookAndAddsNoForeignKey() {
        val name = "migration-4-5-records"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO learning_items VALUES ('retained-book','保留书籍','IN_PROGRESS',120,42,1,'翻到第42页',1,2,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { database ->
            database.query("SELECT id,name,currentPage,firstAction FROM learning_items").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("retained-book", row.getString(0))
                assertEquals("保留书籍", row.getString(1)); assertEquals(42, row.getInt(2)); assertEquals("翻到第42页", row.getString(3))
                assertFalse(row.moveToNext())
            }
            database.query("SELECT COUNT(*) FROM wearable_completed_records").use { row -> row.moveToFirst(); assertEquals(0L, row.getLong(0)) }
            database.query("PRAGMA foreign_key_list(wearable_completed_records)").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun allHistoricalMigrationsReachFiveWithoutClearingExistingUserRows() {
        val name = "migration-1-5-records"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO learning_items VALUES ('first-book','第一版书籍','IN_PROGRESS',100,12,1,'打开第12页',1,2,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).use { database ->
            database.query("SELECT name,currentPage FROM learning_items WHERE id='first-book'").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("第一版书籍", row.getString(0)); assertEquals(12, row.getInt(1))
            }
        }
    }
}
