package com.guanyi.mirra

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.di.checkpointAndCloseDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room compatibility-path probe: cancelling a reader is not permanent epoch retirement. */
@RunWith(AndroidJUnit4::class)
class StorageReaderEpochTest {
    @Test fun closedGeneratedDaoReadDoesNotReopenTheOldOwner() = runBlocking {
        withIsolatedDatabase { database ->
            checkpointAndCloseDatabase(database)
            assertFalse(database.isOpen)
            val attemptedRead = runCatching { database.learningItemDao().get("missing-synthetic-item") }
            assertTrue("A retired DAO reader must reject further access", attemptedRead.isFailure)
            assertFalse("A stale DAO must not recreate a database handle", database.isOpen)
        }
    }

    @Test fun closedTransactionalReaderCannotReopenTheOldOwner() = runBlocking {
        withIsolatedDatabase { database ->
            checkpointAndCloseDatabase(database)
            assertFalse(database.isOpen)
            // Trends.load / Topic.suggestions both retain this same withTransaction read shape.
            val attemptedRead = runCatching {
                database.withTransaction { database.learningItemDao().get("missing-synthetic-item") }
            }
            assertTrue("A retired transactional reader must reject further access", attemptedRead.isFailure)
            assertFalse("A stale withTransaction reader must not reopen the old helper", database.isOpen)
        }
    }

    private suspend fun withIsolatedDatabase(block: suspend (MirraDatabase) -> Unit) = withContext(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-epoch-${UUID.randomUUID()}")
        check(root.mkdir())
        val database = Room.databaseBuilder(context, MirraDatabase::class.java, File(root, "test.sqlite").absolutePath)
            .openHelperFactory(com.guanyi.mirra.data.maintenance.EpochOpenHelperFactory()).build()
        try {
            database.openHelper.writableDatabase
            block(database)
        } finally {
            // A failed probe may expose Room's compatibility helper reopening after Room.close.
            // Close that isolated helper explicitly; never touch the installed Mirra database.
            database.openHelper.close()
            database.close()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
}
