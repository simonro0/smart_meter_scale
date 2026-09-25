package de.simonroder.smartmeterscale.ha

import android.util.Log
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.ScaleReading
import de.simonroder.smartmeterscale.data.User
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

class MqttDiscoveryClient(private val config: MqttConfig) {

    private val deviceJson = JSONObject().apply {
        put("identifiers", JSONArray().put("smartmeterscale_app"))
        put("name", "SmartMeterScale")
        put("model", "Android App")
        put("manufacturer", "simonroder")
    }

    fun publishMeter(meterType: MeterType, value: Double) {
        withClient { client ->
            publishSensor(
                client,
                objectId = meterType.entityBase,
                name = meterType.displayName,
                unit = meterType.unit,
                deviceClass = meterType.deviceClass,
                stateClass = meterType.stateClass,
                value = value.toString()
            )
        }
    }

    fun publishScaleReading(reading: ScaleReading, user: User?) {
        val suffix = user?.entitySuffix() ?: ""
        val label = user?.name?.replaceFirstChar { it.uppercase() }?.let { " $it" } ?: ""
        withClient { client ->
            publishSensor(client, "scale_weight$suffix", "Gewicht$label", "kg", null, "measurement", reading.weightKg.toString())
            reading.bodyFatPercent?.let {
                publishSensor(client, "scale_body_fat$suffix", "Körperfett$label", "%", null, "measurement", it.toString())
            }
            reading.bodyWaterPercent?.let {
                publishSensor(client, "scale_body_water$suffix", "Körperwasser$label", "%", null, "measurement", it.toString())
            }
        }
    }

    private fun publishSensor(
        client: MqttClient,
        objectId: String,
        name: String,
        unit: String,
        deviceClass: String?,
        stateClass: String,
        value: String
    ) {
        val discoveryPayload = JSONObject().apply {
            put("name", name)
            put("unique_id", "smartmeterscale_$objectId")
            put("object_id", objectId)
            put("state_topic", stateTopic(objectId))
            put("unit_of_measurement", unit)
            deviceClass?.let { put("device_class", it) }
            put("state_class", stateClass)
            put("device", deviceJson)
        }.toString()

        client.publish(
            "homeassistant/sensor/$objectId/config",
            MqttMessage(discoveryPayload.toByteArray()).apply { isRetained = true; qos = 1 }
        )
        client.publish(
            stateTopic(objectId),
            MqttMessage(value.toByteArray()).apply { isRetained = true; qos = 1 }
        )
        Log.d("SmartMeter", "MQTT published: $objectId = $value")
    }

    private fun stateTopic(objectId: String) = "smartmeterscale/sensor/$objectId/state"

    private fun withClient(block: (MqttClient) -> Unit) {
        val clientId = "smartmeterscale_${System.currentTimeMillis()}"
        val client = MqttClient("tcp://${config.host}:${config.port}", clientId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            if (config.username.isNotBlank()) {
                userName = config.username
                password = config.password.toCharArray()
            }
        }
        client.connect(opts)
        try {
            block(client)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
