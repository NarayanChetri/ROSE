package dev.narayan.rose.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "rose_update_prefs")

class UpdatePreferences(private val context: Context) {

    private val dataStore = context.updateDataStore

    val lastCheckTimeFlow: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> prefs[KEY_LAST_CHECK_TIME] ?: 0L }

    val autoCheckEnabledFlow: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> prefs[KEY_AUTO_CHECK_ENABLED] ?: true }

    val skippedVersionFlow: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> prefs[KEY_SKIPPED_VERSION] }

    suspend fun getLastCheckTime(): Long {
        return try {
            lastCheckTimeFlow.first()
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun setLastCheckTime(timeMs: Long) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_LAST_CHECK_TIME] = timeMs
            }
        } catch (e: Exception) {
            // Fail silently on persistent write failure
        }
    }

    suspend fun isAutoCheckEnabled(): Boolean {
        return try {
            autoCheckEnabledFlow.first()
        } catch (e: Exception) {
            true
        }
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_AUTO_CHECK_ENABLED] = enabled
            }
        } catch (e: Exception) {
            // Fail silently
        }
    }

    suspend fun getSkippedVersion(): String? {
        return try {
            skippedVersionFlow.first()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setSkippedVersion(version: String) {
        try {
            dataStore.edit { prefs ->
                prefs[KEY_SKIPPED_VERSION] = version
            }
        } catch (e: Exception) {
            // Fail silently
        }
    }

    suspend fun clearSkippedVersion() {
        try {
            dataStore.edit { prefs ->
                prefs.remove(KEY_SKIPPED_VERSION)
            }
        } catch (e: Exception) {
            // Fail silently
        }
    }

    companion object {
        val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
        val KEY_AUTO_CHECK_ENABLED = booleanPreferencesKey("auto_check_enabled")
        val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
    }
}

