package com.gramflix.extensions.config

import org.json.JSONObject
import java.util.Locale

/**
 * Remote scraping rules per provider.
 * JSON structure:
 * {
 *   "version": 1,
 *   "rules": {
 *     "Slug": {
 *       "searchPath": "/search",
 *       "searchParam": "q",
 *       "itemSel": ".item",
 *       "titleSel": ".title",
 *       "urlSel": "a@href",
 *       "embedSel": "iframe@src" // optional, for load links
 *     }
 *   }
 * }
 */
object RulesConfig {
    private const val DEFAULT_URL = "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/rules.json"
    private val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/rules.json"
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

    fun getRules(slug: String): JSONObject? {
        val rules = cached?.optJSONObject("rules") ?: return null
        rules.optJSONObject(slug)?.let { return it }
        val normalized = slug.lowercase(Locale.ROOT)
        val keys = rules.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals(slug, ignoreCase = true) || key.lowercase(Locale.ROOT) == normalized) {
                return rules.optJSONObject(key)
            }
        }
        return null
    }

    fun rulesObject(): JSONObject? = cached?.optJSONObject("rules")

    fun primeFromAssets(androidContext: android.content.Context, assetName: String = "rules.json") {
        try {
            val text = androidContext.assets.open(assetName).bufferedReader().use { it.readText() }
            primeFromString(text)
        } catch (_: Throwable) { }
    }
}
