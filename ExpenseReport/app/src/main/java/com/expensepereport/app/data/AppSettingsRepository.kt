package com.expensepereport.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_SUPABASE_URL = stringPreferencesKey("supabase_url")
        private val KEY_SUPABASE_KEY = stringPreferencesKey("supabase_key")

        // Default values
        const val DEFAULT_SUPABASE_URL = "https://your-supabase-project.supabase.co"
        const val DEFAULT_SUPABASE_KEY = "your-supabase-anon-key"
    }

    val supabaseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SUPABASE_URL] ?: DEFAULT_SUPABASE_URL
    }

    val supabaseKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SUPABASE_KEY] ?: DEFAULT_SUPABASE_KEY
    }

    suspend fun saveSupabaseCredentials(url: String, key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SUPABASE_URL] = url.trim()
            prefs[KEY_SUPABASE_KEY] = key.trim()
        }
    }

    fun saveExcelTemplate(inputBytes: ByteArray): Boolean {
        return try {
            val file = File(context.filesDir, "Note_spese_2026_custom_template.xlsx")
            file.writeBytes(inputBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getExcelTemplateFile(): File? {
        val file = File(context.filesDir, "Note_spese_2026_custom_template.xlsx")
        return if (file.exists()) file else null
    }

    fun hasCustomExcelTemplate(): Boolean {
        return getExcelTemplateFile() != null
    }
}
