package com.hyperion.grabber

import android.content.Context
import androidx.preference.PreferenceManager
import com.hyperion.grabber.common.R as CommonR
import com.hyperion.grabber.common.util.Preferences
import org.json.JSONArray
import org.json.JSONObject

data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val priority: Int
)

class ServerProfileStore(context: Context) {
    private val context = context.applicationContext
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.context)
    private val preferences = Preferences(this.context)

    fun list(): List<ServerProfile> {
        val raw = sharedPreferences.getString(PROFILES_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val host = item.optString("host")
                    val port = item.optInt("port", -1)
                    val priority = item.optInt("priority", 100)
                    if (id.isNotBlank() && name.isNotBlank() && host.isNotBlank()
                        && port in 1..65535) {
                        add(ServerProfile(id, name, host, port, priority))
                    }
                }
            }
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    fun activeId(): String? = sharedPreferences.getString(ACTIVE_PROFILE_KEY, null)

    fun ensureCurrentProfile(): List<ServerProfile> {
        val profiles = list()
        if (profiles.isNotEmpty()) return profiles
        saveCurrent("Current server")
        return list()
    }

    fun saveCurrent(name: String): ServerProfile? {
        val host = sharedPreferences.getString(
            context.resources.getString(CommonR.string.pref_key_host), null
        )?.trim().orEmpty()
        val port = sharedPreferences.getString(
            context.resources.getString(CommonR.string.pref_key_port), null
        )?.toIntOrNull() ?: -1
        val priority = sharedPreferences.getString(
            context.resources.getString(CommonR.string.pref_key_priority), "100"
        )
            ?.toIntOrNull() ?: 100
        if (host.isBlank() || port !in 1..65535) return null

        val cleanName = name.trim().ifBlank { "Server" }
        val profiles = list().toMutableList()
        val existing = profiles.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        val profile = ServerProfile(
            existing?.id ?: "profile-${System.currentTimeMillis()}",
            cleanName,
            host,
            port,
            priority
        )
        if (existing == null) {
            profiles.add(profile)
        } else {
            profiles[profiles.indexOf(existing)] = profile
        }
        write(profiles)
        sharedPreferences.edit().putString(ACTIVE_PROFILE_KEY, profile.id).apply()
        return profile
    }

    fun activate(profile: ServerProfile) {
        preferences.putString(CommonR.string.pref_key_host, profile.host)
        preferences.putInt(CommonR.string.pref_key_port, profile.port)
        preferences.putString(CommonR.string.pref_key_priority, profile.priority.toString())
        sharedPreferences.edit().putString(ACTIVE_PROFILE_KEY, profile.id).apply()
    }

    fun delete(profile: ServerProfile) {
        val remaining = list().filterNot { it.id == profile.id }
        write(remaining)
        if (activeId() == profile.id) {
            if (remaining.isEmpty()) {
                sharedPreferences.edit().remove(ACTIVE_PROFILE_KEY).apply()
            } else {
                activate(remaining.first())
            }
        }
    }

    private fun write(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("host", profile.host)
                put("port", profile.port)
                put("priority", profile.priority)
            })
        }
        sharedPreferences.edit().putString(PROFILES_KEY, array.toString()).apply()
    }

    companion object {
        private const val PROFILES_KEY = "hyperion_server_profiles"
        private const val ACTIVE_PROFILE_KEY = "hyperion_active_server_profile"
    }
}
