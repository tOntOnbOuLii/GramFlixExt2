package com.gramflix.extensions.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.LinkedHashSet

class ConfigDrivenProvider : MainAPI() {
    override var name = "GramFlix Dynamic"
    override var mainUrl = "https://webpanel.invalid"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Rule(
        val searchPath: String,
        val searchParam: String,
        val itemSel: String,
        val titleSel: String,
        val urlSel: String,
        val embedSel: String?
    )

    private fun parseRule(slug: String): Rule? {
        val r = RulesConfig.getRules(slug) ?: return null
        return Rule(
            r.optString("searchPath", "/search"),
            r.optString("searchParam", "q"),
            r.optString("itemSel", ""),
            r.optString("titleSel", ""),
            r.optString("urlSel", ""),
            r.optString("embedSel", null)
        ).takeIf { it.itemSel.isNotBlank() && it.urlSel.isNotBlank() }
    }

    private fun selectAttrOrText(root: org.jsoup.nodes.Element, selector: String): String? {
        val at = selector.split("@", limit = 2)
        val css = at[0]
        val el = root.selectFirst(css) ?: return null
        return if (at.size == 2) el.attr(at[1]).takeIf { it.isNotBlank() } else el.text().takeIf { it.isNotBlank() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ConcurrentLinkedQueue<SearchResponse>()
        val providers = RemoteConfig.providersObject()
        providers?.keys()?.forEach { key ->
            val slug = key as String
            val item = providers.optJSONObject(slug) ?: return@forEach
            val baseUrl = item.optString("baseUrl", null) ?: return@forEach
            val rule = parseRule(slug)
            val baseUri = runCatching { URI(baseUrl) }.getOrNull()
            val baseHost = baseUri?.host
            try {
                val url = buildSearchUrl(baseUrl, rule, query) ?: return@forEach
                val res = app.get(url, referer = baseUrl)
                val doc = res.document
                if (rule != null) {
                    doc.select(rule.itemSel).forEach { card ->
                        val title = selectAttrOrText(card, rule.titleSel) ?: return@forEach
                        val href = selectAttrOrText(card, rule.urlSel) ?: return@forEach
                        val absUrl = resolveAgainst(baseUrl, href) ?: return@forEach
                        results.add(newMovieSearchResponse(title, absUrl, TvType.Movie))
                    }
                } else {
                    val seen = LinkedHashSet<String>()
                    val anchors = doc.select("a[href]")
                    for (anchor in anchors) {
                        val href = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: continue
                        if (seen.contains(href)) continue
                        val candidateHost = runCatching { URI(href).host }.getOrNull()
                        if (baseHost != null && candidateHost != null && candidateHost != baseHost) continue
                        val title = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
                        if (title.isBlank()) continue
                        results.add(newMovieSearchResponse(title, href, TvType.Movie))
                        seen.add(href)
                        if (seen.size >= 30) break
                    }
                }
            } catch (_: Throwable) { /* ignore site errors */ }
        }
        return results.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = url).document
            val title = doc.selectFirst("title")?.text()?.trim()?.ifBlank { null } ?: name
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) { }
        } catch (_: Throwable) { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(data, referer = data).text
            val doc = Jsoup.parse(html, data)
            // naive: collect iframe/src and anchor hrefs to extractor
            val candidates = mutableSetOf<String>()
            doc.select("iframe[src]").mapNotNull { it.absUrl("src") }.toCollection(candidates)
            doc.select("a[href]").mapNotNull { it.absUrl("href") }.filter { it.contains("http") }.toCollection(candidates)
            candidates.take(20).forEach { link ->
                try { loadExtractor(link, data, subtitleCallback, callback) } catch (_: Throwable) {}
            }
            candidates.isNotEmpty()
        } catch (_: Throwable) { false }
    }

    private fun buildSearchUrl(baseUrl: String, rule: Rule?, query: String): String? {
        val encodedQuery = try {
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            query
        }

        if (rule != null) {
            val base = try { URI(baseUrl) } catch (_: Exception) { return null }
            val rawPath = rule.searchPath.ifBlank { "/" }
            val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
            val hasQuery = path.contains("?")
            val separator = when {
                !hasQuery -> "?"
                path.endsWith("?") || path.endsWith("&") -> ""
                else -> "&"
            }
            val queryParam = rule.searchParam.ifBlank { "q" }
            val relative = "$path$separator$queryParam=$encodedQuery"
            return resolveAgainst(base, relative)
        }

        val fallbackUrls = listOf(
            "/?s=$encodedQuery",
            "/search?q=$encodedQuery",
            "/recherche?q=$encodedQuery",
            "/?story=$encodedQuery"
        )
        return fallbackUrls.firstNotNullOfOrNull { resolveAgainst(baseUrl, it) }
    }

    private fun resolveAgainst(baseUrl: String, append: String): String? {
        val base = try { URI(baseUrl) } catch (_: Exception) { return null }
        return resolveAgainst(base, append)
    }

    private fun resolveAgainst(base: URI, append: String): String? {
        return try {
            base.resolve(append).toString()
        } catch (_: Exception) {
            null
        }
    }
}
