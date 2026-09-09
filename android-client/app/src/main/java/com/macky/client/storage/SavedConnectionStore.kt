package com.macky.client.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SavedConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val savedAt: Long = System.currentTimeMillis()
)

/**
 * Local persistent store for saved connection endpoints (e.g. Tailscale / fixed LAN IPs).
 * Does NOT persist transient room codes — queries GET /current-room dynamically to obtain active room codes.
 */
class SavedConnectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("acuity_saved_connections", Context.MODE_PRIVATE)

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _savedConnections = MutableStateFlow<List<SavedConnection>>(loadFromPrefs())
    val savedConnections: StateFlow<List<SavedConnection>> = _savedConnections.asStateFlow()

    private fun loadFromPrefs(): List<SavedConnection> {
        val json = prefs.getString(KEY_SAVED_CONNECTIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedConnection>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveToPrefs(list: List<SavedConnection>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_SAVED_CONNECTIONS, json).apply()
        _savedConnections.value = list
    }

    fun saveConnection(name: String, serverUrl: String) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        val cleanName = name.trim().ifBlank {
            cleanUrl.removePrefix("http://").removePrefix("ws://").removePrefix("https://").removePrefix("wss://")
        }

        val currentList = _savedConnections.value.toMutableList()
        // If an entry with the same serverUrl already exists, update name and timestamp
        val existingIndex = currentList.indexOfFirst { it.serverUrl.equals(cleanUrl, ignoreCase = true) }
        if (existingIndex >= 0) {
            currentList[existingIndex] = currentList[existingIndex].copy(
                name = cleanName,
                savedAt = System.currentTimeMillis()
            )
        } else {
            currentList.add(
                0,
                SavedConnection(
                    name = cleanName,
                    serverUrl = cleanUrl,
                    savedAt = System.currentTimeMillis()
                )
            )
        }
        saveToPrefs(currentList)
    }

    fun deleteConnection(id: String) {
        val currentList = _savedConnections.value.filterNot { it.id == id }
        saveToPrefs(currentList)
    }

    /**
     * Queries GET /current-room on the specified signaling server host.
     * Returns the live 6-character room code, or throws an exception if host is offline.
     */
    suspend fun fetchCurrentRoom(serverUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            var httpBase = serverUrl.trim().removeSuffix("/")
            if (httpBase.startsWith("ws://")) {
                httpBase = "http://" + httpBase.removePrefix("ws://")
            } else if (httpBase.startsWith("wss://")) {
                httpBase = "https://" + httpBase.removePrefix("wss://")
            } else if (!httpBase.startsWith("http://") && !httpBase.startsWith("https://")) {
                httpBase = "http://$httpBase"
            }

            val request = Request.Builder()
                .url("$httpBase/current-room")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Host returned HTTP ${response.code}: No active room")
                    )
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val roomCode = json.optString("room_code", "")
                if (roomCode.length == 6) {
                    Result.success(roomCode)
                } else {
                    Result.failure(Exception("Invalid room code response from host"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val KEY_SAVED_CONNECTIONS = "saved_connections_json"
    }
}
