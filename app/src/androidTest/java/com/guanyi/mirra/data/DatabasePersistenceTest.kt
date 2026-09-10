package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabasePersistenceTest {
    @Test
    fun bookSurvivesDatabaseCloseAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "persistence-test.db"
        context.deleteDatabase(databaseName)
        var database = Room.databaseBuilder(context, MirraDatabase::class.java, databaseName).build()
        val created = DefaultLearningItemRepository(database).create("持久化测试", 120, 33)
        database.close()

        database = Room.databaseBuilder(context, MirraDatabase::class.java, databaseName).build()
        val restored = database.learningItemDao().get(created.id)

        assertEquals("持久化测试", restored?.name)
        assertEquals(33, restored?.currentPage)
        database.close()
        context.deleteDatabase(databaseName)
    }
}
