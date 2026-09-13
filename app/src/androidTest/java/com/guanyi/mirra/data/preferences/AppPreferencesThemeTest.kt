package com.guanyi.mirra.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesThemeTest {
    @Test
    fun themeDefaultsToBlueAndPersistsWithoutChangingDestination() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "theme-${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DefaultAppPreferencesRepository(store)

        assertEquals(MirraThemeId.BLUE, repository.themeId.first())
        repository.setLastDestination(com.guanyi.mirra.navigation.TopLevelDestination.Knowledge)
        repository.setThemeId(MirraThemeId.NIGHT)

        assertEquals(MirraThemeId.NIGHT, repository.themeId.first())
        assertEquals(com.guanyi.mirra.navigation.TopLevelDestination.Knowledge, repository.lastDestination.first())
    }
}
