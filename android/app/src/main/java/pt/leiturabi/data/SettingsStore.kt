package pt.leiturabi.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "leiturabi_settings")

data class AppSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val author: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private val keyServer = stringPreferencesKey("server_url")
    private val keyApi = stringPreferencesKey("api_key")
    private val keyAuthor = stringPreferencesKey("author")

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            serverUrl = preferences[keyServer].orEmpty(),
            apiKey = preferences[keyApi].orEmpty(),
            author = preferences[keyAuthor].orEmpty(),
        )
    }

    suspend fun save(settings: AppSettings) {
        val normalized = Net.normalizeUrl(settings.serverUrl)
        context.dataStore.edit { preferences ->
            preferences[keyServer] = normalized
            preferences[keyApi] = settings.apiKey.trim()
            preferences[keyAuthor] = settings.author.trim()
        }
        Net.configure(normalized, settings.apiKey)
    }
}
