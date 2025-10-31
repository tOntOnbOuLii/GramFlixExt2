package com.gramflix.extensions.config

import org.json.JSONObject

/**
 * Remote configuration loader for provider base URLs.
 * Expected JSON structure:
 * {
 *   "version": 1,
 *   "providers": {
 *     "example": {"name": "Example Site", "baseUrl": "https://example.com"}
 *   }
 * }
 */
object RemoteConfig {
    // Hosted config consumed by the extensions
    private const val DEFAULT_URL = "https://cs.tafili.fr/providers.json"

    // Simple in-memory cache
    @Volatile
    private var cached: JSONObject? = null

    // Replace with Cloudstream's networking (e.g., app.get) when the runtime is available.
    private fun httpGet(url: String): String {
        // Placeholder to avoid adding external deps in skeleton.
        // At runtime in Cloudstream, replace calls with app.get(url).text
        throw NotImplementedError("Networking is provided by Cloudstream at runtime.")
    }

    fun getProviderBaseUrlOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val providers = json.optJSONObject("providers") ?: return fallback
        val item = providers.optJSONObject(key) ?: return fallback
        return item.optString("baseUrl", fallback)
    }

    fun getProviderNameOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val providers = json.optJSONObject("providers") ?: return fallback
        val item = providers.optJSONObject(key) ?: return fallback
        return item.optString("name", fallback)
    }

    fun primeFromString(jsonString: String) {
        cached = try {
            JSONObject(jsonString)
        } catch (_: Throwable) {
            null
        }
    }

    // In Cloudstream runtime, this would be a suspend function using app.get
    fun refreshFromNetwork(url: String = DEFAULT_URL) {
        val text = httpGet(url)
        primeFromString(text)
    }
}
