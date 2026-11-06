package com.planboq.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "planboq_settings")

data class Settings(
    val serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
    val apiKey: String = "",
    /** user-held secret for device-to-device cloud sync (>= 12 chars) */
    val syncId: String = "",
    /** conversions used in the current month window (free tier) */
    val conversionsThisMonth: Int = 0,
    /** "YYYY-MM" stamp for the conversion counter */
    val conversionMonth: String = "",
    /** debug builds can flip entitlement without Play Console */
    val debugProOverride: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val syncId = stringPreferencesKey("sync_id")
        val conversions = intPreferencesKey("conversions_this_month")
        val month = stringPreferencesKey("conversion_month")
        val debugPro = booleanPreferencesKey("debug_pro_override")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = p[Keys.serverUrl] ?: DEFAULT_SERVER_URL,
            apiKey = p[Keys.apiKey] ?: "",
            syncId = p[Keys.syncId] ?: "",
            conversionsThisMonth = p[Keys.conversions] ?: 0,
            conversionMonth = p[Keys.month] ?: "",
            debugProOverride = p[Keys.debugPro] ?: false,
        )
    }

    suspend fun setServer(url: String, key: String) {
        context.dataStore.edit { p ->
            p[Keys.serverUrl] = url.trim().removeSuffix("/")
            p[Keys.apiKey] = key.trim()
        }
    }

    suspend fun setDebugProOverride(on: Boolean) {
        context.dataStore.edit { it[Keys.debugPro] = on }
    }

    suspend fun setSyncId(value: String) {
        context.dataStore.edit { it[Keys.syncId] = value.trim() }
    }

    /** Increments the monthly conversion counter, resetting when the month rolls over. */
    suspend fun recordConversion() {
        val stamp = currentMonthStamp()
        context.dataStore.edit { p ->
            val sameMonth = p[Keys.month] == stamp
            p[Keys.month] = stamp
            p[Keys.conversions] = if (sameMonth) (p[Keys.conversions] ?: 0) + 1 else 1
        }
    }

    companion object {
        /** Production processing server; editable in Settings. */
        const val DEFAULT_SERVER_URL = "https://api.corecivil.engineer"

        fun currentMonthStamp(): String {
            val cal = java.util.Calendar.getInstance()
            return "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
        }
    }
}
