package com.guanyi.mirra.data.maintenance

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Room's compatibility helper must never reopen a retired storage generation. */
class EpochOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        val lifecycle = Any()
        var retired = false
        var closeCompleted = false
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase
                get() = synchronized(lifecycle) { check(!retired) { "Storage database epoch is closed" }; helper.writableDatabase }
            override val readableDatabase: SupportSQLiteDatabase
                get() = synchronized(lifecycle) { check(!retired) { "Storage database epoch is closed" }; helper.readableDatabase }
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(lifecycle) {
                check(!retired) { "Storage database epoch is closed" }
                helper.setWriteAheadLoggingEnabled(enabled)
            }
            override fun close() = synchronized(lifecycle) {
                // Retire before closing; even a close failure cannot admit a late opener.
                retired = true
                if (!closeCompleted) { helper.close(); closeCompleted = true }
            }
        }
    }
}
