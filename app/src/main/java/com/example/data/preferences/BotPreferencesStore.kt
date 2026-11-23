package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.botPrefsDataStore by preferencesDataStore(name = "neurobot_prefs")

data class BotUserPreferences(
    val activeProfileId: Long = 1L,
    val isUrduLanguage: Boolean = false,
    val isDarkTheme: Boolean = true,
    val apiServerUrl: String = "",
    val apiToken: String = "",
    val isScreenshotUploadEnabled: Boolean = false
)

class BotPreferencesStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val IS_URDU = booleanPreferencesKey("is_urdu_language")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val API_SERVER_URL = stringPreferencesKey("api_server_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val SCREENSHOT_UPLOAD_ENABLED = booleanPreferencesKey("screenshot_upload_enabled")
    }

    val preferences: Flow<BotUserPreferences> = context.botPrefsDataStore.data.map { prefs ->
        BotUserPreferences(
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID] ?: 1L,
            isUrduLanguage = prefs[Keys.IS_URDU] ?: false,
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true,
            apiServerUrl = prefs[Keys.API_SERVER_URL] ?: "",
            apiToken = prefs[Keys.API_TOKEN] ?: "",
            isScreenshotUploadEnabled = prefs[Keys.SCREENSHOT_UPLOAD_ENABLED] ?: false
        )
    }

    suspend fun setActiveProfileId(profileId: Long) {
        context.botPrefsDataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
    }

    suspend fun setUrduLanguage(enabled: Boolean) {
        context.botPrefsDataStore.edit { it[Keys.IS_URDU] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.botPrefsDataStore.edit { it[Keys.IS_DARK_THEME] = enabled }
    }

    suspend fun saveApiServerSettings(url: String, token: String) {
        val normalizedUrl = url.trim().trimEnd('/')
        val normalizedToken = token.trim()
        context.botPrefsDataStore.edit { prefs ->
            prefs[Keys.API_SERVER_URL] = normalizedUrl
            prefs[Keys.API_TOKEN] = normalizedToken
        }
    }

    suspend fun setScreenshotUploadEnabled(enabled: Boolean) {
        context.botPrefsDataStore.edit { it[Keys.SCREENSHOT_UPLOAD_ENABLED] = enabled }
    }
}
