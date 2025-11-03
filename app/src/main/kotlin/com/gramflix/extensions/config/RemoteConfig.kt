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
    // Hosted config consumed by the extensions (public mirror)
    private const val DEFAULT_URL = "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/providers.json"
    private val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        // GitHub mirror (optional, updated by panel if GitHub sync enabled)
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/providers.json"
    )
    private val refreshLock = Any()

    // Simple in-memory cache
    @Volatile
    private var cached: JSONObject? = null

    // Basic network fetch using standard library. Cloudstream may override with its own client.
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

    fun getProviderBaseUrlOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val providers = json.optJSONObject("providers") ?: return fallback
        val item = providers.optJSONObject(key) ?: return fallback
        val value = item.optString("baseUrl").takeIf { it.isNotBlank() }
        return value ?: fallback
    }

    fun getProviderNameOrNull(key: String, fallback: String? = null): String? {
        val json = cached ?: return fallback
        val providers = json.optJSONObject("providers") ?: return fallback
        val item = providers.optJSONObject(key) ?: return fallback
        val value = item.optString("name").takeIf { it.isNotBlank() }
        return value ?: fallback
    }

    fun primeFromString(jsonString: String) {
        cached = try {
            JSONObject(jsonString)
        } catch (_: Throwable) {
            null
        }
    }

    // Try to refresh from network; swallow errors to avoid crashing the plugin when offline.
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
        if (!force && providersObject() != null) return true
        synchronized(refreshLock) {
            if (!force && providersObject() != null) return true
            val urls = FALLBACK_URLS.distinct()
            for (candidate in urls) {
                if (refreshFromNetwork(candidate)) return true
            }
            return providersObject() != null
        }
    }

    fun providersObject(): JSONObject? = cached?.optJSONObject("providers")

    // Load a bundled JSON from assets (e.g., app/src/main/assets/providers.json)
    fun primeFromAssets(androidContext: android.content.Context, assetName: String = "providers.json") {
        try {
            val text = androidContext.assets.open(assetName).bufferedReader().use { it.readText() }
            primeFromString(text)
        } catch (_: Throwable) {
            // ignore missing asset
        }
    }
}
