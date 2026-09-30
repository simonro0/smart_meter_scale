package de.simonroder.smartmeterscale.data

enum class MeterType(
    val displayName: String,
    val unit: String,
    val entityBase: String,
    val stateClass: String,
    val deviceClass: String?
) {
    Scale("Scale", "kg", "scale", "measurement", null),
    Gas("Gas Meter", "m³", "gas_meter", "total_increasing", "gas"),
    Electricity("Electricity Meter", "kWh", "electricity_meter", "total_increasing", "energy"),
    Water("Water Meter", "m³", "water_meter", "total_increasing", "water"),
    Odometer("Odometer", "km", "odometer", "total_increasing", "distance")
}
