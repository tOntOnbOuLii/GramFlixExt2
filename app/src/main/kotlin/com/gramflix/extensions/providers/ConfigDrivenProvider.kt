package com.gramflix.extensions.providers

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.gramflix.extensions.config.HostersConfig
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
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
        val embed = (r.opt("embedSel") as? String)?.takeUnless { it.isBlank() }
        return Rule(
            r.optString("searchPath", "/search"),
            r.optString("searchParam", "q"),
            r.optString("itemSel", ""),
            r.optString("titleSel", ""),
            r.optString("urlSel", ""),
            embed
        ).takeIf { it.itemSel.isNotBlank() && it.urlSel.isNotBlank() }
    }

    private fun selectAttrOrText(root: org.jsoup.nodes.Element, selector: String): String? {
        val at = selector.split("@", limit = 2)
        val css = at[0]
        val el = root.selectFirst(css) ?: return null
        return if (at.size == 2) el.attr(at[1]).takeIf { it.isNotBlank() } else el.text().takeIf { it.isNotBlank() }
    }

    private fun ensureRemoteConfigs() {
        RemoteConfig.ensureLoaded()
        RulesConfig.ensureLoaded()
        HostersConfig.ensureLoaded()
    }

    private fun extractWithRule(
        doc: Document,
        baseUrl: String,
        rule: Rule,
        limit: Int = 30
    ): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val responses = ArrayList<SearchResponse>()
        val cards = doc.select(rule.itemSel)
        for (card in cards) {
            val title = selectAttrOrText(card, rule.titleSel) ?: continue
            val href = selectAttrOrText(card, rule.urlSel) ?: continue
            val absUrl = resolveAgainst(baseUrl, href) ?: continue
            if (!seen.add(absUrl)) continue
            responses.add(newMovieSearchResponse(title, absUrl, TvType.Movie))
            if (responses.size >= limit) break
        }
        return responses
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureRemoteConfigs()
        val results = mutableListOf<SearchResponse>()
        val providers = RemoteConfig.providersObject() ?: return emptyList()
        val keys = providers.keys()
        while (keys.hasNext()) {
            val slug = keys.next()
            val item = providers.optJSONObject(slug) ?: continue
            val baseUrl = item.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            val rule = parseRule(slug)
            val baseUri = runCatching { URI(baseUrl) }.getOrNull()
            val baseHost = baseUri?.host
            try {
                val url = buildSearchUrl(baseUrl, rule, query) ?: continue
                val res = app.get(url, referer = baseUrl)
                val doc = res.document
                if (rule != null) {
                    results.addAll(extractWithRule(doc, baseUrl, rule))
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
            } catch (_: Throwable) { /* ignore site errors */ continue }
        }
        return results
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

            val replaced = when {
                rawPath.contains("%s") -> rawPath.replace("%s", encodedQuery)
                rawPath.contains("{query}") -> rawPath.replace("{query}", encodedQuery)
                rawPath.endsWith("=") && rawPath.contains("?") -> rawPath + encodedQuery
                else -> null
            }
            if (replaced != null) {
                return resolveAgainst(base, replaced)
            }

            val path = when {
                rawPath.startsWith("/") || rawPath.startsWith("?") -> rawPath
                else -> "/$rawPath"
            }
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureRemoteConfigs()
        val providers = RemoteConfig.providersObject() ?: return newHomePageResponse(emptyList(), hasNext = false)
        val lists = ArrayList<HomePageList>()
        val keys = providers.keys()
        var processed = 0
        val maxProviders = if (page <= 1) 5 else 0
        while (keys.hasNext() && (maxProviders == 0 || processed < maxProviders)) {
            val slug = keys.next()
            val providerObj = providers.optJSONObject(slug) ?: continue
            val baseUrl = providerObj.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            val rule = parseRule(slug) ?: continue
            try {
                val title = providerObj.optString("name", slug)
                val doc = app.get(baseUrl, referer = baseUrl).document
                val items = extractWithRule(doc, baseUrl, rule, limit = 20)
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items))
                    processed++
                }
            } catch (_: Throwable) {
                // ignore failed provider
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }
}
