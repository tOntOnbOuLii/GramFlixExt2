package com.gramflix.extensions.config

import org.json.JSONObject

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
    private const val DEFAULT_URL = "https://cs.tafili.fr/rules.json"
    private val FALLBACK_URLS = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlixExt2/main/rules.json"
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
        val urls = if (url == DEFAULT_URL) FALLBACK_URLS else listOf(url)
        for (u in urls) {
            try {
                val text = httpGet(u)
                primeFromString(text)
                return
            } catch (_: Throwable) { }
        }
    }

    fun getRules(slug: String): JSONObject? {
        val json = cached ?: return null
        return json.optJSONObject("rules")?.optJSONObject(slug)
    }

    fun primeFromAssets(androidContext: android.content.Context, assetName: String = "rules.json") {
        try {
            val text = androidContext.assets.open(assetName).bufferedReader().use { it.readText() }
            primeFromString(text)
        } catch (_: Throwable) { }
    }
}
