package com.gramflix.extensions.config

import org.json.JSONObject

/**
 * Remote hosters mapping loader.
 * Expected JSON structure:
 * {
 *   "version": 1,
 *   "hosters": {
 *     "uqload": {"name":"Uqload","url":"https://uqload.*"}
 *   }
 * }
 */
object HostersConfig {
    private const val DEFAULT_URL = "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/hosters.json"
    private val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/hosters.json"
    )
    private val refreshLock = Any()

    @Volatile
    private var cached: JSONObject? = null

    private fun httpGet(url: String): String {
        return try {
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.getInputStream().bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            throw e
        }
    }

    fun primeFromString(jsonString: String) {
        cached = try { JSONObject(jsonString) } catch (_: Throwable) { null }
    }

    fun refreshFromNetwork(url: String = DEFAULT_URL): Boolean {
        return try {
            val text = httpGet(url)
            primeFromString(text)
            cached != null
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureLoaded(force: Boolean = false): Boolean {
        if (!force && cached != null) return true
        synchronized(refreshLock) {
            if (!force && cached != null) return true
            val urls = FALLBACK_URLS.distinct()
            for (candidate in urls) {
                if (refreshFromNetwork(candidate)) return true
            }
            return cached != null
        }
    }

    fun getHosterUrlOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val obj = json.optJSONObject("hosters") ?: return fallback
        val item = obj.optJSONObject(key) ?: return fallback
        val value = item.optString("url").takeIf { it.isNotBlank() }
        return value ?: fallback
    }

    fun getHosterNameOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val obj = json.optJSONObject("hosters") ?: return fallback
        val item = obj.optJSONObject(key) ?: return fallback
        val value = item.optString("name").takeIf { it.isNotBlank() }
        return value ?: fallback
    }

    fun hostersObject(): JSONObject? = cached?.optJSONObject("hosters")
}
