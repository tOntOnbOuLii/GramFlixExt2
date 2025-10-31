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
    private const val DEFAULT_URL = "https://cs.tafili.fr/hosters.json"

    @Volatile
    private var cached: JSONObject? = null

    private fun httpGet(url: String): String {
        throw NotImplementedError("Networking is provided by Cloudstream at runtime.")
    }

    fun primeFromString(jsonString: String) {
        cached = try { JSONObject(jsonString) } catch (_: Throwable) { null }
    }

    fun refreshFromNetwork(url: String = DEFAULT_URL) {
        val text = httpGet(url)
        primeFromString(text)
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

