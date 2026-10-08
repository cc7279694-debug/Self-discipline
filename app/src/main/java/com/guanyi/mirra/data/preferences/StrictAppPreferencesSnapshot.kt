package com.guanyi.mirra.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.guanyi.mirra.navigation.TopLevelDestination
import java.util.Collections
import kotlinx.coroutines.flow.first

/** Separate from the UI repository so existing UI-only implementations remain compatible. */
interface StrictAppPreferencesReader {
    suspend fun readStrictSnapshot(): StrictAppPreferencesSnapshot
}

data class PreferenceSnapshotValue<T>(
    val value: T,
    val present: Boolean,
)

/** The four settings eligible for a future portable backup. */
data class PortableAppPreferencesSnapshot(
    val lastDestination: PreferenceSnapshotValue<TopLevelDestination>,
    val themeId: PreferenceSnapshotValue<MirraThemeId>,
    val dndEnabled: PreferenceSnapshotValue<Boolean>,
    val crossAppInterventionEnabled: PreferenceSnapshotValue<Boolean>,
)

/** Complete old settings, including unknown keys and the absence of missing keys. */
class OriginalAppPreferencesSnapshot internal constructor(
    values: Map<String, StoredPreferenceValue>,
) {
    val values: Map<String, StoredPreferenceValue> =
        Collections.unmodifiableMap(LinkedHashMap(values))
}

sealed interface StoredPreferenceValue {
    data class BooleanValue(val value: Boolean) : StoredPreferenceValue
    data class FloatValue(val value: Float) : StoredPreferenceValue
    data class DoubleValue(val value: Double) : StoredPreferenceValue
    data class IntValue(val value: Int) : StoredPreferenceValue
    data class LongValue(val value: Long) : StoredPreferenceValue
    data class StringValue(val value: String) : StoredPreferenceValue
    class StringSetValue(value: Set<String>) : StoredPreferenceValue {
        val value: Set<String> = Collections.unmodifiableSet(LinkedHashSet(value))
    }

    class ByteArrayValue(value: ByteArray) : StoredPreferenceValue {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
    }
}

data class StrictAppPreferencesSnapshot(
    val portable: PortableAppPreferencesSnapshot,
    val original: OriginalAppPreferencesSnapshot,
)

internal suspend fun readStrictAppPreferencesSnapshot(
    dataStore: DataStore<Preferences>,
): StrictAppPreferencesSnapshot {
    // One successful emission supplies both views; read/corruption/cancellation failures propagate.
    val preferences = dataStore.data.first()
    val original = OriginalAppPreferencesSnapshot(preferences.asMap().entries.associate { (key, value) ->
        val storedValue = when (value) {
            is Boolean -> StoredPreferenceValue.BooleanValue(value)
            is Float -> StoredPreferenceValue.FloatValue(value)
            is Double -> StoredPreferenceValue.DoubleValue(value)
            is Int -> StoredPreferenceValue.IntValue(value)
            is Long -> StoredPreferenceValue.LongValue(value)
            is String -> StoredPreferenceValue.StringValue(value)
            is Set<*> -> StoredPreferenceValue.StringSetValue(value.mapTo(LinkedHashSet()) { item ->
                require(item is String) { "Invalid string set preference: ${key.name}" }
                item
            })
            is ByteArray -> StoredPreferenceValue.ByteArrayValue(value)
            else -> throw IllegalArgumentException("Unsupported preference type: ${key.name}")
        }
        key.name to storedValue
    })

    val destination = original.valueOfType<StoredPreferenceValue.StringValue>("last_destination")
    val theme = original.valueOfType<StoredPreferenceValue.StringValue>("theme_id")
    val dnd = original.valueOfType<StoredPreferenceValue.BooleanValue>("dnd_enabled")
    val crossApp = original.valueOfType<StoredPreferenceValue.BooleanValue>("cross_app_intervention_enabled")

    val effectiveDestination = if (destination == null) {
        TopLevelDestination.Start
    } else {
        requireNotNull(TopLevelDestination.entries.firstOrNull { it.storageValue == destination.value }) {
            "Invalid preference value: last_destination"
        }
    }
    val effectiveTheme = if (theme == null) {
        MirraThemeId.BLUE
    } else {
        requireNotNull(MirraThemeId.entries.firstOrNull { it.storageValue == theme.value }) {
            "Invalid preference value: theme_id"
        }
    }

    return StrictAppPreferencesSnapshot(
        portable = PortableAppPreferencesSnapshot(
            lastDestination = PreferenceSnapshotValue(effectiveDestination, destination != null),
            themeId = PreferenceSnapshotValue(effectiveTheme, theme != null),
            dndEnabled = PreferenceSnapshotValue(dnd?.value ?: false, dnd != null),
            crossAppInterventionEnabled = PreferenceSnapshotValue(crossApp?.value ?: false, crossApp != null),
        ),
        original = original,
    )
}

private inline fun <reified T : StoredPreferenceValue> OriginalAppPreferencesSnapshot.valueOfType(
    key: String,
): T? {
    val value = values[key] ?: return null
    require(value is T) { "Invalid preference type: $key" }
    return value
}
