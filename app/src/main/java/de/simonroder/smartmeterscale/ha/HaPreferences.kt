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

    // SAF tree URI persisted after folder picker — used for reliable writing on Android 10+
    var backupUri: String
        get() = prefs.getString("backup_uri", "") ?: ""
        set(v) = prefs.edit().putString("backup_uri", v).apply()

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_api_key", v).apply()

    var mqttHost: String
        get() = prefs.getString("mqtt_host", "") ?: ""
        set(v) = prefs.edit().putString("mqtt_host", v).apply()

    var mqttPort: Int
        get() = prefs.getInt("mqtt_port", 1883)
        set(v) = prefs.edit().putInt("mqtt_port", v).apply()

    var mqttUsername: String
        get() = prefs.getString("mqtt_username", "") ?: ""
        set(v) = prefs.edit().putString("mqtt_username", v).apply()

    var mqttPassword: String
        get() = prefs.getString("mqtt_password", "") ?: ""
        set(v) = prefs.edit().putString("mqtt_password", v).apply()

    fun isConfigured() = baseUrl.isNotBlank() && token.isNotBlank()

    fun isMqttConfigured() = mqttHost.isNotBlank()

    fun toConfig() = HomeAssistantConfig(baseUrl, token)

    fun toMqttConfig() = MqttConfig(mqttHost, mqttPort, mqttUsername, mqttPassword)
}
