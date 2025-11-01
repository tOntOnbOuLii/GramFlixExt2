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

    fun refreshFromNetwork(url: String = DEFAULT_URL) {
        try {
            val text = httpGet(url)
            primeFromString(text)
        } catch (_: Throwable) { }
    }

    fun getHosterUrlOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val obj = json.optJSONObject("hosters") ?: return fallback
        val item = obj.optJSONObject(key) ?: return fallback
        return item.optString("url", fallback)
    }

    fun getHosterNameOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val obj = json.optJSONObject("hosters") ?: return fallback
        val item = obj.optJSONObject(key) ?: return fallback
        return item.optString("name", fallback)
    }
}
