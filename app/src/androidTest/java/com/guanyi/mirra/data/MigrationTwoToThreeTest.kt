package com.guanyi.mirra.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MirraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.domain.DefaultSearchEngine
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MigrationTwoToThreeTest {
    private val databaseName = "migration-2-3"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MirraDatabase::class.java,
    )

    @Test
    fun migrationPreservesAllVersionTwoDataAndAddsEmptyTopicSearchStructures() {
        helper.createDatabase(databaseName, 2).apply {
            execSQL("INSERT INTO learning_items VALUES ('book','迁移书','IN_PROGRESS',200,40,1,'翻到第40页',1,2,NULL)")
            execSQL("INSERT INTO study_intents VALUES ('intent','book',3,4,5,5,'CONVERTED',NULL)")
            execSQL("INSERT INTO study_sessions VALUES ('session','book','intent',5,NULL,10,40,45,45,'NORMAL','心理账户总结',NULL)")
            execSQL("INSERT INTO notes VALUES ('note','book','session','UNDERSTANDING','迁移笔记',43,6,7)")
            execSQL("INSERT INTO image_assets VALUES ('image','note','images/test.jpg','图片说明',1200,900,12345,8)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 3, true, MIGRATION_2_3).apply {
            query("SELECT name,currentPage FROM learning_items WHERE id='book'").use {
                it.moveToFirst(); assertEquals("迁移书", it.getString(0)); assertEquals(40, it.getInt(1))
            }
            query("SELECT content,pageNumber FROM notes WHERE id='note'").use {
                it.moveToFirst(); assertEquals("迁移笔记", it.getString(0)); assertEquals(43, it.getInt(1))
            }
            query("SELECT localPath,caption FROM image_assets WHERE id='image'").use {
                it.moveToFirst(); assertEquals("images/test.jpg", it.getString(0)); assertEquals("图片说明", it.getString(1))
            }
            query("SELECT generatedSummary FROM study_sessions WHERE id='session'").use {
                it.moveToFirst(); assertEquals("心理账户总结", it.getString(0))
            }
            query("SELECT COUNT(*) FROM topics").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            query("SELECT COUNT(*) FROM note_topic_cross_refs").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            query("SELECT COUNT(*) FROM search_fts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            execSQL("INSERT INTO topics VALUES ('topic','心理账户',9)")
            execSQL("INSERT INTO note_topic_cross_refs VALUES ('note','topic')")
            execSQL("INSERT INTO search_fts(entityType,entityId,searchableText,normalizedTokens) VALUES ('NOTE','note','迁移笔记','迁移 移笔 笔记')")
            query("SELECT entityId FROM search_fts WHERE search_fts MATCH ?", arrayOf("\"笔记\"")).use {
                it.moveToFirst(); assertEquals("note", it.getString(0))
            }
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, MirraDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_2_3)
            .build()
        try {
            runBlocking {
                val health = SearchIndexRebuilder(migrated, DefaultSearchEngine()).ensureConsistent()
                assertEquals(true, health.rebuilt)
                assertEquals(4, migrated.searchFtsDao().count())
                assertEquals(true, migrated.searchFtsDao().search("\"迁移\"", 60).any { it.entityId == "note" })
            }
        } finally {
            migrated.close()
        }
        context.deleteDatabase(databaseName)
    }
}
