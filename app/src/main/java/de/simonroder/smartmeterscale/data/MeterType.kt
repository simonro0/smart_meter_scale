package de.simonroder.smartmeterscale.data

enum class MeterType(val displayName: String, val unit: String, val entityBase: String) {
    Scale("Waage", "kg", "scale"),
    Gas("Gaszähler", "m³", "gas_meter"),
    Electricity("Stromzähler", "kWh", "electricity_meter"),
    Water("Wasserzähler", "m³", "water_meter")
}
