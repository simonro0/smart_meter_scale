package de.simonroder.smartmeterscale.ha

import android.content.Context
import de.simonroder.smartmeterscale.data.User
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("users", Context.MODE_PRIVATE)

    fun getUsers(): List<User> {
        val raw = prefs.getString("users", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                User(obj.getString("id"), obj.getString("name"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addUser(name: String) {
        val users = getUsers().toMutableList()
        users.add(User(UUID.randomUUID().toString(), name.trim()))
        save(users)
    }

    fun removeUser(id: String) {
        save(getUsers().filter { it.id != id })
    }

    private fun save(users: List<User>) {
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(JSONObject().apply { put("id", u.id); put("name", u.name) })
        }
        prefs.edit().putString("users", arr.toString()).apply()
    }
}
