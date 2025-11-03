package com.gramflix.extensions.providers

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Locale
import kotlin.math.max

class ConfigDrivenProvider : MainAPI() {
    override var name = "GramFlix Dynamic"
    override var mainUrl = "https://webpanel.invalid"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    init {
        sequentialMainPage = true
    }

    private data class Rule(
        val searchPath: String,
        val searchParam: String,
        val itemSel: String,
        val titleSel: String,
        val urlSel: String,
        val embedSel: String?
    )

    private data class ProviderMeta(
        val slug: String,
        val displayName: String,
        val baseUrl: String,
        val rule: Rule?
    )

    private val whitespaceRegex = Regex("\\s+")
    private val qualityTokens = setOf(
        "HDLIGHT", "HD-LIGHT", "HDCAM", "CAM", "TRUEFRENCH", "FRENCH", "MULTI", "MULTI-VF",
        "VOSTFR", "VF", "VO", "BDRIP", "HDRIP", "WEBDL", "WEB-DL", "DVDRIP", "BLURAY", "BRRIP",
        "X265", "X264", "H265", "H264", "UHD", "4K", "1080P", "720P", "480P", "HQ", "REMUX",
        "TS", "TELESYNC", "HDS", "HC", "TRUEHD", "DTS", "SD"
    )
    private val noiseTitleRegex = Regex("(?i)^(HD|HDLIGHT|HDCAM|CAM|FRENCH|TRUEFRENCH|VOSTFR|VF|VO|MULTI|SERIE|SERIES|FILM|EPISODE|EP)$")
    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")

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

    private fun ensureRemoteConfigs() {
        RemoteConfig.ensureLoaded()
        RulesConfig.ensureLoaded()
    }

    private fun gatherProviders(): List<ProviderMeta> {
        val providers = RemoteConfig.providersObject() ?: return emptyList()
        val list = mutableListOf<ProviderMeta>()
        val iterator = providers.keys()
        while (iterator.hasNext()) {
            val slug = iterator.next()
            val info = providers.optJSONObject(slug) ?: continue
            val baseUrl = info.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            val displayName = info.optString("name").takeIf { it.isNotBlank() } ?: slug
            list += ProviderMeta(
                slug = slug,
                displayName = displayName,
                baseUrl = baseUrl,
                rule = parseRule(slug)
            )
        }
        return list.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    private fun sanitizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.replace('\u00A0', ' ')
        text = whitespaceRegex.replace(text, " ").trim()
        if (text.isEmpty()) return ""
        val words = text.split(' ')
        val filtered = words.mapNotNull { word ->
            val trimmed = word.trim('-', '/', '\\', '[', ']', '(', ')', '.', ':', ';', ',', '•', '|')
            if (trimmed.isBlank()) {
                null
            } else {
                val upper = trimmed.uppercase(Locale.ROOT)
                val isResolution = upper.matches(Regex("\\d{3,4}P"))
                if (qualityTokens.contains(upper) || isResolution) {
                    null
                } else {
                    word
                }
            }
        }
        val candidate = filtered.joinToString(" ").trim()
        val result = if (candidate.isNotBlank()) candidate else text
        return result.replace(Regex("\\s+"), " ").trim('-', '•', '|', ':', '.', ';', ',')
    }

    private fun chooseTitle(candidates: List<String>, providerName: String, query: String?): String? {
        if (candidates.isEmpty()) return null
        val sanitized = candidates
            .map { sanitizeTitle(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (sanitized.isEmpty()) return null

        val queryLower = query?.lowercase(Locale.ROOT)
        val queryMatches = if (!queryLower.isNullOrBlank()) {
            sanitized.filter { it.lowercase(Locale.ROOT).contains(queryLower) }
        } else emptyList()

        val prioritized = when {
            queryMatches.isNotEmpty() -> queryMatches
            else -> sanitized
        }

        return prioritized
            .asSequence()
            .filterNot { noiseTitleRegex.matches(it) }
            .filterNot { it.equals(providerName, ignoreCase = true) }
            .firstOrNull()
            ?: prioritized.firstOrNull()
    }

    private fun collectSelectorValues(card: Element, selector: String): List<String> {
        val trimSel = selector.trim()
        if (trimSel.isBlank()) return emptyList()
        val parts = trimSel.split("@", limit = 2)
        val css = parts[0].trim()
        val attr = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        if (css.isBlank()) return emptyList()
        val values = mutableListOf<String>()
        for (element in card.select(css)) {
            if (attr != null) {
                val value = element.attr(attr)
                if (!value.isNullOrBlank()) values += value
            } else {
                val text = element.text()
                if (!text.isNullOrBlank()) values += text
            }
            val attributes = listOf("title", "alt", "data-title", "data-name")
            for (attribute in attributes) {
                val attrValue = element.attr(attribute)
                if (!attrValue.isNullOrBlank()) values += attrValue
            }
        }
        return values
    }

    private fun resolveTitle(card: Element, rule: Rule, providerName: String, query: String?): String? {
        val selectors = rule.titleSel.split(",")
        val candidates = mutableListOf<String>()
        selectors.forEach { candidates += collectSelectorValues(card, it) }
        val cardAttributes = listOf("data-title", "title", "aria-label")
        cardAttributes.forEach { attr ->
            val value = card.attr(attr)
            if (!value.isNullOrBlank()) candidates += value
        }
        val fallbackImg = card.selectFirst("img")
        if (fallbackImg != null) {
            val alt = fallbackImg.attr("alt")
            if (!alt.isNullOrBlank()) candidates += alt
            val title = fallbackImg.attr("title")
            if (!title.isNullOrBlank()) candidates += title
        }
        return chooseTitle(candidates, providerName, query)
    }

    private fun extractPoster(card: Element, baseUrl: String): String? {
        val imageCandidates = listOf(
            "img[data-src]",
            "img[data-original]",
            "img[data-lazy-src]",
            "img[data-defer-src]",
            "img[data-placeholder]",
            "img[srcset]",
            "img[src]"
        )
        val attributesOrder = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-defer-src",
            "data-placeholder",
            "data-srcset",
            "data-echo",
            "srcset",
            "src"
        )
        for (selector in imageCandidates) {
            val img = card.selectFirst(selector) ?: continue
            for (attr in attributesOrder) {
                val raw = img.attr(attr)
                if (raw.isNullOrBlank()) continue
                val url = if (attr.contains("srcset", ignoreCase = true)) {
                    raw.split(",")
                        .mapNotNull { it.trim().split(" ").firstOrNull() }
                        .firstOrNull { it.isNotBlank() }
                } else raw
                if (url.isNullOrBlank()) continue
                val resolved = resolveAgainst(baseUrl, url) ?: url
                if (resolved.startsWith("http", ignoreCase = true)) return resolved
            }
            val style = img.attr("style")
            if (style.isNotBlank()) {
                val match = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)
                val rawUrl = match?.groupValues?.getOrNull(1)
                if (!rawUrl.isNullOrBlank()) {
                    val resolved = resolveAgainst(baseUrl, rawUrl) ?: rawUrl
                    if (resolved.startsWith("http", ignoreCase = true)) return resolved
                }
            }
        }
        return null
    }

    private fun extractYearFrom(title: String): Int? {
        val match = yearRegex.find(title) ?: return null
        return match.value.toIntOrNull()
    }

    private fun createSearchResponse(
        providerName: String,
        title: String,
        url: String,
        poster: String?,
        includeProvider: Boolean
    ): SearchResponse {
        val displayTitle = if (includeProvider) {
            "$providerName - $title"
        } else {
            title
        }
        val response = newMovieSearchResponse(displayTitle, url, TvType.Movie)
        if (!poster.isNullOrBlank()) {
            response.posterUrl = poster
        }
        val year = extractYearFrom(title)
        if (year != null) {
            response.year = year
        }
        return response
    }

    private fun resolveHref(card: Element, rule: Rule, baseUrl: String): String? {
        val parts = rule.urlSel.split("@", limit = 2)
        val css = parts.getOrNull(0)?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val attr = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "href"
        val element = card.selectFirst(css) ?: return null
        val raw = element.attr(attr).takeIf { it.isNotBlank() } ?: return null
        return resolveAgainst(baseUrl, raw)
    }

    private fun extractWithRule(
        meta: ProviderMeta,
        doc: Document,
        query: String?,
        dedupe: MutableSet<String>,
        limit: Int,
        includeProvider: Boolean
    ): List<SearchResponse> {
        val rule = meta.rule ?: return emptyList()
        val responses = ArrayList<SearchResponse>()
        val cards = doc.select(rule.itemSel)
        for (card in cards) {
            val href = resolveHref(card, rule, meta.baseUrl) ?: continue
            val dedupeKey = "${meta.slug}::$href"
            if (!dedupe.add(dedupeKey)) continue
            val title = resolveTitle(card, rule, meta.displayName, query) ?: continue
            val poster = extractPoster(card, meta.baseUrl)
            responses += createSearchResponse(meta.displayName, title, href, poster, includeProvider)
            if (responses.size >= limit) break
        }
        return responses
    }

    private fun fallbackExtraction(
        meta: ProviderMeta,
        doc: Document,
        query: String?,
        dedupe: MutableSet<String>,
        limit: Int,
        includeProvider: Boolean
    ): List<SearchResponse> {
        val responses = ArrayList<SearchResponse>()
        val baseUri = runCatching { URI(meta.baseUrl) }.getOrNull()
        val baseHost = baseUri?.host
        val anchors = doc.select("a[href]")
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) continue
            val resolved = resolveAgainst(meta.baseUrl, href) ?: continue
            val host = runCatching { URI(resolved).host }.getOrNull()
            if (baseHost != null && host != null && !host.endsWith(baseHost)) continue
            val rawTitle = anchor.text().ifBlank { anchor.attr("title") }
            val title = sanitizeTitle(rawTitle)
            if (title.isBlank()) continue
            if (!query.isNullOrBlank() && !title.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) continue
            val dedupeKey = "${meta.slug}::$resolved"
            if (!dedupe.add(dedupeKey)) continue
            responses += createSearchResponse(meta.displayName, title, resolved, null, includeProvider)
            if (responses.size >= limit) break
        }
        return responses
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        if (metas.isEmpty()) return emptyList()
        val dedupe = hashSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        for (meta in metas) {
            try {
                val url = buildSearchUrl(meta.baseUrl, meta.rule, query) ?: continue
                val response = app.get(url, referer = meta.baseUrl)
                val doc = response.document
                val items = if (meta.rule != null) {
                    extractWithRule(meta, doc, query, dedupe, limit = 25, includeProvider = true)
                } else {
                    fallbackExtraction(meta, doc, query, dedupe, limit = 15, includeProvider = true)
                }
                results += items
            } catch (_: Throwable) {
                // Ignore providers that fail
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        if (metas.isEmpty()) return newHomePageResponse(emptyList(), hasNext = false)
        val pageSize = 5
        val startIndex = max(0, (page - 1) * pageSize)
        if (startIndex >= metas.size) return newHomePageResponse(emptyList(), hasNext = false)
        val slice = metas.drop(startIndex).take(pageSize)
        val lists = mutableListOf<HomePageList>()
        for (meta in slice) {
            val rule = meta.rule ?: continue
            try {
                val response = app.get(meta.baseUrl, referer = meta.baseUrl)
                val doc = response.document
                val dedupe = hashSetOf<String>()
                val items = extractWithRule(meta, doc, query = null, dedupe = dedupe, limit = 20, includeProvider = false)
                if (items.isNotEmpty()) {
                    lists += HomePageList(meta.displayName, items)
                }
            } catch (_: Throwable) {
                // Ignore providers that fail
            }
        }
        val hasNext = startIndex + pageSize < metas.size
        return newHomePageResponse(lists, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = url).document
            val title = doc.selectFirst("title")?.text()?.trim()?.ifBlank { null } ?: name
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) { }
        } catch (_: Throwable) {
            null
        }
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
            val candidates = mutableSetOf<String>()
            doc.select("iframe[src]").mapNotNull { it.absUrl("src") }.toCollection(candidates)
            doc.select("a[href]").mapNotNull { it.absUrl("href") }.filter { it.startsWith("http") }.toCollection(candidates)
            candidates.take(20).forEach { link ->
                try {
                    loadExtractor(link, data, subtitleCallback, callback)
                } catch (_: Throwable) {
                }
            }
            candidates.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildSearchUrl(baseUrl: String, rule: Rule?, query: String): String? {
        val encodedQuery = try {
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            query
        }

        if (rule != null) {
            val base = try {
                URI(baseUrl)
            } catch (_: Exception) {
                return null
            }
            val rawPath = rule.searchPath.ifBlank { "/" }
            if (rawPath.contains("%s")) {
                return resolveAgainst(base, rawPath.replace("%s", encodedQuery))
            }
            if (rawPath.contains("{query}")) {
                return resolveAgainst(base, rawPath.replace("{query}", encodedQuery))
            }
            if (rawPath.contains("?") && rawPath.endsWith("=")) {
                return resolveAgainst(base, rawPath + encodedQuery)
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
        val base = try {
            URI(baseUrl)
        } catch (_: Exception) {
            return null
        }
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
