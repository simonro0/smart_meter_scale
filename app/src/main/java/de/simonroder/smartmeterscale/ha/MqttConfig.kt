package de.simonroder.smartmeterscale.ha

data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)
