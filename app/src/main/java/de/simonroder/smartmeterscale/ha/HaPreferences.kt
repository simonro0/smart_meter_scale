package de.simonroder.smartmeterscale.ha

import android.content.Context

class HaPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ha_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var backupPath: String
        get() = prefs.getString("backup_path", "") ?: ""
        set(v) = prefs.edit().putString("backup_path", v).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_api_key", v).apply()

    fun isConfigured() = baseUrl.isNotBlank() && token.isNotBlank()

    fun toConfig() = HomeAssistantConfig(baseUrl, token)
}
