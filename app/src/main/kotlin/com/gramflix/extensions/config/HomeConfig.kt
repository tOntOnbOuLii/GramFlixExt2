package com.gramflix.extensions.config

import org.json.JSONObject

/**
 * Remote curated home sections for fallback UI.
 * {
 *   "version": 1,
 *   "sections": [
 *     {
 *       "name": "Trending",
 *       "items": [
 *         {"title":"...", "imdbId":"tt...", "poster":"...", "year":2024}
 *       ]
 *     }
 *   ]
 * }
 */
object HomeConfig {
    private const val DEFAULT_URL =
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/home.json"
    private val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/home.json"
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
        cached = try {
            JSONObject(jsonString)
        } catch (_: Throwable) {
            null
        }
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

    fun sectionsArray() = cached?.optJSONArray("sections")

    fun primeFromAssets(androidContext: android.content.Context, assetName: String = "home.json") {
        try {
            val text = androidContext.assets.open(assetName).bufferedReader().use { it.readText() }
            primeFromString(text)
        } catch (_: Throwable) {
            // ignore missing asset
        }
    }
}
