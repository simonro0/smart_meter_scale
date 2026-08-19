package de.simonroder.smartmeterscale.data

enum class MeterType(
    val displayName: String,
    val unit: String,
    val entityBase: String,
    val stateClass: String,
    val deviceClass: String?
) {
    Scale("Waage", "kg", "scale", "measurement", null),
    Gas("Gaszähler", "m³", "gas_meter", "total_increasing", "gas"),
    Electricity("Stromzähler", "kWh", "electricity_meter", "total_increasing", "energy"),
    Water("Wasserzähler", "m³", "water_meter", "total_increasing", "water")
}
