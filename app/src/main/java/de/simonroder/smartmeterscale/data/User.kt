package de.simonroder.smartmeterscale.data

data class User(val id: String, val name: String) {
    fun entitySuffix() = "_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
}
