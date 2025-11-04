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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import com.gramflix.extensions.config.HomeConfig
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class ConfigDrivenProvider : MainAPI() {
    override var name = "GramFlix Dynamic"
    override var mainUrl = "https://webpanel.invalid"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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

    private data class SearchItem(
        val response: SearchResponse,
        val score: Int
    )

    private data class AjaxRequest(
        val url: String,
        val referer: String
    )

    private data class AjaxConfig(
        val url: String,
        val playMethod: String?,
        val playerApi: String?,
        val classItem: Int?
    )

    private data class PlayerOption(
        val nume: String,
        val type: String?,
        val label: String?
    )

    private data class ImdbItem(
        val imdbId: String,
        val title: String,
        val poster: String?,
        val year: Int?
    )

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        private const val ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val ACCEPT_JSON = "application/json, text/plain, */*"
        private const val ACCEPT_AJAX = "application/json, text/javascript, */*;q=0.01"
        private const val ACCEPT_LANGUAGE = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    private fun originFrom(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val portPart = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$portPart"
        }.getOrNull()
    }

    private fun secFetchSite(targetUrl: String, referer: String?): String {
        if (referer.isNullOrBlank()) return "none"
        val targetOrigin = originFrom(targetUrl) ?: return "cross-site"
        val refererOrigin = originFrom(referer) ?: return "cross-site"
        return if (targetOrigin.equals(refererOrigin, ignoreCase = true)) {
            "same-origin"
        } else {
            "cross-site"
        }
    }

    private fun buildCommonHeaders(): LinkedHashMap<String, String> {
        val headers = LinkedHashMap<String, String>(6)
        headers["User-Agent"] = BROWSER_USER_AGENT
        headers["Accept-Language"] = ACCEPT_LANGUAGE
        headers["Cache-Control"] = "no-cache"
        headers["Pragma"] = "no-cache"
        headers["Connection"] = "keep-alive"
        return headers
    }

    private fun buildHtmlHeaders(
        targetUrl: String,
        referer: String?,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = buildCommonHeaders()
        headers["Accept"] = ACCEPT_HTML
        headers["Upgrade-Insecure-Requests"] = "1"
        headers["Sec-Fetch-Dest"] = "document"
        headers["Sec-Fetch-Mode"] = "navigate"
        headers["Sec-Fetch-Site"] = secFetchSite(targetUrl, referer)
        headers["Sec-Fetch-User"] = "?1"
        headers.putAll(extra)
        return headers
    }

    private fun buildJsonHeaders(
        targetUrl: String,
        referer: String?,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = buildCommonHeaders()
        headers["Accept"] = ACCEPT_JSON
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = secFetchSite(targetUrl, referer)
        val origin = originFrom(referer) ?: originFrom(targetUrl)
        if (origin != null) {
            headers["Origin"] = origin
        }
        headers.putAll(extra)
        return headers
    }

    private fun buildAjaxHeaders(
        targetUrl: String,
        referer: String?,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = buildCommonHeaders()
        headers["Accept"] = ACCEPT_AJAX
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = secFetchSite(targetUrl, referer)
        val origin = originFrom(referer) ?: originFrom(targetUrl)
        if (origin != null) {
            headers["Origin"] = origin
        }
        headers.putAll(extra)
        return headers
    }

    private suspend fun fetchHtml(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ) = app.get(url, referer = referer, headers = buildHtmlHeaders(url, referer, extraHeaders))

    private suspend fun fetchJson(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ) = app.get(url, referer = referer, headers = buildJsonHeaders(url, referer, extraHeaders))

    private val whitespaceRegex = Regex("\\s+")
    private val qualityTokens = setOf(
        "HDLIGHT", "HD-LIGHT", "HDCAM", "CAM", "TRUEFRENCH", "FRENCH", "MULTI", "MULTI-VF",
        "VOSTFR", "VF", "VO", "BDRIP", "HDRIP", "WEBDL", "WEB-DL", "DVDRIP", "BLURAY", "BRRIP",
        "X265", "X264", "H265", "H264", "UHD", "4K", "1080P", "720P", "480P", "HQ", "REMUX",
        "TS", "TELESYNC", "HDS", "HC", "TRUEHD", "DTS", "SD"
    )
    private val noiseTitleRegex = Regex("(?i)^(HD|HDLIGHT|HDCAM|CAM|FRENCH|TRUEFRENCH|VOSTFR|VF|VO|MULTI|SERIE|SERIES|FILM|EPISODE|EP)$")
    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val accentRegex = Regex("\\p{Mn}+")
    private val urlSlugLock = Any()
    private val urlSlugCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 1024
    }
    private val fallbackCache = object : LinkedHashMap<String, ImdbItem>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImdbItem>?): Boolean = size > 256
    }

    private fun canonicalizeUrl(url: String): String = url.trim().trimEnd('/')

    private fun rememberSlugForUrl(url: String, slug: String) {
        val normalized = canonicalizeUrl(url)
        synchronized(urlSlugLock) {
            urlSlugCache[normalized] = slug
            if (normalized != url) {
                urlSlugCache[url] = slug
            }
        }
    }

    private fun findSlugForUrl(url: String): String? {
        val normalized = canonicalizeUrl(url)
        synchronized(urlSlugLock) {
            return urlSlugCache[url] ?: urlSlugCache[normalized]
        }
    }

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

    private fun normalizeHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()
    }

    private fun findMetaBySlug(metas: List<ProviderMeta>, slug: String): ProviderMeta? {
        return metas.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
    }

    private fun findMetaByUrl(url: String, metas: List<ProviderMeta>): ProviderMeta? {
        val normalizedHost = normalizeHost(url)
        if (!normalizedHost.isNullOrBlank()) {
            val byHost = metas.firstOrNull { meta ->
                val host = normalizeHost(meta.baseUrl)
                host != null && (normalizedHost == host || normalizedHost.endsWith(".$host"))
            }
            if (byHost != null) return byHost
        }
        val normalizedUrl = canonicalizeUrl(url)
        return metas.firstOrNull { normalizedUrl.startsWith(canonicalizeUrl(it.baseUrl), ignoreCase = true) }
    }

    private fun cacheImdbItem(item: ImdbItem) {
        synchronized(fallbackCache) {
            fallbackCache[item.imdbId] = item
        }
    }

    private fun getCachedImdbItem(imdbId: String): ImdbItem? {
        synchronized(fallbackCache) {
            return fallbackCache[imdbId]
        }
    }

    private fun sanitizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.replace('\u00A0', ' ')
        text = whitespaceRegex.replace(text, " ").trim()
        if (text.isEmpty()) return ""
        val words = text.split(' ')
        val filtered = words.mapNotNull { word ->
            val trimmed = word.trim('-', '/', '\\', '[', ']', '(', ')', '.', ':', ';', ',', '|')
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
        return result.replace(Regex("\\s+"), " ").trim('-', '|', ':', '.', ';', ',')
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

    private fun normalizeSearchText(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = accentRegex.replace(normalized, "")
        val lowered = stripped.lowercase(Locale.ROOT)
        return whitespaceRegex.replace(lowered, " ").trim()
    }

    private fun titleMatchesQuery(title: String, query: String): Boolean {
        val normalizedTitle = normalizeSearchText(title)
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) return true
        if (normalizedTitle.contains(normalizedQuery)) return true
        val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        return tokens.all { normalizedTitle.contains(it) }
    }

    private fun computeMatchScore(title: String, query: String?): Int {
        if (query.isNullOrBlank()) return 0
        val normalizedTitle = normalizeSearchText(title)
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) return 0
        var score = 0
        if (normalizedTitle == normalizedQuery) score += 100
        if (normalizedTitle.startsWith(normalizedQuery)) score += 40
        if (normalizedTitle.contains(normalizedQuery)) score += 25
        val titleTokens = normalizedTitle.split(' ').filter { it.isNotBlank() }
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return score
        var matchedTokens = 0
        for (token in queryTokens) {
            if (titleTokens.contains(token)) {
                matchedTokens++
            }
        }
        score += matchedTokens * 15
        val startIndex = normalizedTitle.indexOf(normalizedQuery)
        if (startIndex >= 0) score += 20
        val unmatched = titleTokens.size - matchedTokens
        if (unmatched > 0) {
            score -= unmatched * 2
        }
        val lengthDiff = abs(titleTokens.size - queryTokens.size)
        if (lengthDiff > 0) {
            score -= lengthDiff * 3
        }
        return score.coerceAtLeast(0)
    }

    private suspend fun fetchImdbDetails(imdbId: String): ImdbItem? {
        val first = imdbId.firstOrNull()?.lowercaseChar() ?: return null
        val url = "https://v3.sg.media-imdb.com/suggestion/$first/$imdbId.json"
        return runCatching { app.get(url, referer = "https://www.imdb.com/") }.getOrNull()
            ?.let { response ->
                val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
                val array = json.optJSONArray("d") ?: return null
                (0 until array.length())
                    .asSequence()
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { imdbId.equals(it.optString("id"), ignoreCase = true) }
                    ?.let { obj ->
                        val title = obj.optString("l")
                        if (title.isBlank()) return null
                        val image = obj.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() }
                        val year = obj.optInt("y").takeIf { it > 0 }
                        ImdbItem(imdbId, title, image, year)
                    }
            }?.also { cacheImdbItem(it) }
    }

    private fun buildFallbackSearchResponse(item: ImdbItem): SearchResponse {
        val url = "imdb://${item.imdbId}"
        val response = newMovieSearchResponse(item.title, url, TvType.Movie)
        item.poster?.let { response.posterUrl = it }
        item.year?.let { response.year = it }
        return response
    }

    private suspend fun searchFallbackImdb(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val first = trimmed.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetterOrDigit() } ?: '0'
        val encoded = runCatching { URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()) }.getOrElse { trimmed }
        val url = "https://v2.sg.media-imdb.com/suggestion/$first/$encoded.json"
        val response = runCatching { app.get(url, referer = "https://www.imdb.com/") }.getOrNull()
            ?: return emptyList()
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("d") ?: return emptyList()
        val results = ArrayList<SearchResponse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = item.optString("qid")
            if (!type.equals("movie", ignoreCase = true)) continue
            val imdbId = item.optString("id")
            val title = item.optString("l")
            if (imdbId.isBlank() || title.isBlank()) continue
            val poster = item.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() }
            val year = item.optInt("y").takeIf { it > 0 }
            val imdbItem = ImdbItem(imdbId, title, poster, year)
            cacheImdbItem(imdbItem)
            results += buildFallbackSearchResponse(imdbItem)
            if (results.size >= 30) break
        }
        return results
    }

    private fun loadHomeFallback(): List<HomePageList> {
        val sections = HomeConfig.sectionsArray() ?: return emptyList()
        val lists = mutableListOf<HomePageList>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val name = section.optString("name").ifBlank { "GramFlix" }
            val items = section.optJSONArray("items") ?: continue
            val responses = mutableListOf<SearchResponse>()
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val imdbId = item.optString("imdbId")
                val title = item.optString("title")
                if (imdbId.isBlank() || title.isBlank()) continue
                val poster = item.optString("poster").takeIf { it.isNotBlank() }
                val year = item.optInt("year").takeIf { it > 0 }
                val imdbItem = ImdbItem(imdbId, title, poster, year)
                cacheImdbItem(imdbItem)
                responses += buildFallbackSearchResponse(imdbItem)
            }
            if (responses.isNotEmpty()) {
                lists += HomePageList(name, responses)
            }
        }
        return lists
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

    private fun collectAttributeValues(root: Element, selector: String, baseUrl: String): List<String> {
        val trimmed = selector.trim()
        if (trimmed.isBlank()) return emptyList()
        val parts = trimmed.split("@", limit = 2)
        val css = parts.getOrNull(0)?.trim().takeIf { !it.isNullOrBlank() } ?: return emptyList()
        val attr = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "src"
        val elements = when {
            css.equals("self", ignoreCase = true) -> listOf(root)
            else -> root.select(css)
        }
        if (elements.isEmpty()) return emptyList()
        val values = mutableListOf<String>()
        for (element in elements) {
            val attributeCandidates = mutableListOf(attr)
            attributeCandidates += listOf("data-$attr", "data-src", "data-url", "data-href", "data-link")
            if (!attr.equals("href", ignoreCase = true)) {
                attributeCandidates += "href"
            }
            if (!attr.equals("src", ignoreCase = true)) {
                attributeCandidates += "src"
            }
            for (candidate in attributeCandidates.distinct()) {
                val raw = element.attr(candidate).takeIf { it.isNotBlank() } ?: continue
                val resolved = resolveAgainst(baseUrl, raw)
                val absolute = if (candidate.equals("href", ignoreCase = true) || candidate.equals("src", ignoreCase = true)) {
                    element.absUrl(candidate)
                } else {
                    element.absUrl(candidate)
                }
                val value = when {
                    !resolved.isNullOrBlank() -> resolved
                    !absolute.isNullOrBlank() -> absolute
                    else -> raw
                }
                if (value.startsWith("http")) {
                    values += value
                    break
                }
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

    private fun gatherEmbedCandidates(doc: Document, pageUrl: String, meta: ProviderMeta?): List<String> {
        val selectors = mutableListOf<String>()
        val ruleSelectors = meta?.rule?.embedSel
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        if (!ruleSelectors.isNullOrEmpty()) {
            selectors += ruleSelectors
        }
        selectors += listOf(
            "iframe@src",
            "iframe@data-src",
            "iframe@data-url",
            "iframe@data-player",
            "iframe[data-src]@data-src",
            "iframe[data-url]@data-url",
            "video source@src",
            "video@src",
            "a[data-player]@data-player",
            "a[data-url]@data-url",
            "a[data-href]@data-href"
        )
        val results = linkedSetOf<String>()
        selectors.forEach { selector ->
            results += collectAttributeValues(doc, selector, pageUrl)
        }
        return results.toList()
    }

    private fun gatherFallbackCandidates(doc: Document, pageUrl: String): List<String> {
        val results = linkedSetOf<String>()
        doc.select("iframe, video, video source").forEach { element ->
            val attributes = listOf("src", "data-src", "data-url", "data-href", "data-link")
            for (attribute in attributes) {
                val raw = element.attr(attribute).takeIf { it.isNotBlank() } ?: continue
                val resolved = resolveAgainst(pageUrl, raw)
                val absolute = element.absUrl(attribute)
                val value = when {
                    !resolved.isNullOrBlank() -> resolved
                    !absolute.isNullOrBlank() -> absolute
                    else -> raw
                }
                if (value.startsWith("http")) {
                    results += value
                    break
                }
            }
        }
        doc.select("a[href], a[data-url], a[data-href], a[data-link]").forEach { element ->
            val attributes = listOf("href", "data-url", "data-href", "data-link")
            for (attribute in attributes) {
                val raw = element.attr(attribute).takeIf { it.isNotBlank() } ?: continue
                val resolved = resolveAgainst(pageUrl, raw)
                val absolute = element.absUrl(attribute)
                val value = when {
                    !resolved.isNullOrBlank() -> resolved
                    !absolute.isNullOrBlank() -> absolute
                    else -> raw
                }
                if (value.startsWith("http")) {
                    results += value
                    break
                }
            }
        }
        return results.toList()
    }

    private fun gatherAjaxRequests(doc: Document, pageUrl: String, meta: ProviderMeta?): List<AjaxRequest> {
        val results = linkedSetOf<AjaxRequest>()
        val referer = meta?.baseUrl ?: pageUrl
        val onclickRegex = Regex("getxfield\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
        val argumentsRegex = Regex("[\"']([^\"']+)[\"']")
        doc.select("[onclick*=\"getxfield\"]").forEach { element ->
            val onclick = element.attr("onclick")
            val match = onclickRegex.find(onclick) ?: return@forEach
            val args = argumentsRegex.findAll(match.groupValues.getOrNull(1) ?: "")
                .map { it.groupValues[1] }
                .toList()
            if (args.size >= 3) {
                val id = args[0]
                val xfield = args[1]
                val token = args[2]
                val relative = "/engine/ajax/getxfield.php?id=$id&xfield=$xfield&token=$token"
                val resolved = resolveAgainst(pageUrl, relative) ?: resolveAgainst(referer, relative)
                if (!resolved.isNullOrBlank()) {
                    results += AjaxRequest(resolved, referer)
                }
            }
        }
        return results.toList()
    }

    private fun parseAjaxConfig(doc: Document, pageUrl: String): AjaxConfig? {
        val html = doc.outerHtml()
        val regex = Regex("""var\s+dtAjax\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html)
        if (match != null) {
            val parsed = runCatching {
                val json = JSONObject(match.groupValues[1])
                val rawUrl = json.optString("url").takeIf { it.isNotBlank() }
                val resolvedUrl = rawUrl?.let { resolveAgainst(pageUrl, it) ?: it } ?: return@runCatching null
                val playerApi = json.optString("player_api").takeIf { it.isNotBlank() }
                    ?.let { resolveAgainst(pageUrl, it) ?: it }
                val playMethod = json.optString("play_method").takeIf { it.isNotBlank() }
                val classItem = when {
                    json.has("classitem") -> {
                        json.optInt("classitem").takeIf { it > 0 } ?: json.optString("classitem").toIntOrNull()
                    }
                    else -> null
                }
                AjaxConfig(resolvedUrl, playMethod, playerApi, classItem)
            }.getOrNull()
            if (parsed != null) {
                return parsed
            }
        }

        val dooPlayOptions = doc.select("li.dooplay_player_option")
        if (dooPlayOptions.isNotEmpty()) {
            val fallbackUrl = resolveAgainst(pageUrl, "/wp-admin/admin-ajax.php")
                ?.takeIf { it.isNotBlank() }
            if (fallbackUrl != null) {
                val classItem = dooPlayOptions.size.takeIf { it > 0 }
                return AjaxConfig(fallbackUrl, playMethod = null, playerApi = null, classItem = classItem)
            }
        }

        return null
    }

    private fun extractPostId(doc: Document): String? {
        val body = doc.selectFirst("body") ?: return null
        val classes = body.classNames()
        return classes.firstOrNull { it.startsWith("postid-", ignoreCase = true) }
            ?.removePrefix("postid-")
            ?.takeIf { it.all { ch -> ch.isDigit() } }
    }

    private fun inferPlayerType(doc: Document, pageUrl: String): String {
        val body = doc.selectFirst("body")
        val classes = body?.classNames()?.map { it.lowercase(Locale.ROOT) } ?: emptyList()
        return when {
            classes.any { it.contains("episode") } || pageUrl.contains("/episode", ignoreCase = true) -> "episode"
            classes.any { it.contains("season") } || pageUrl.contains("/season", ignoreCase = true) -> "season"
            classes.any { it.contains("tvshow") || it.contains("tvshows") || it.contains("type-tv") } ||
                pageUrl.contains("/tvshows", ignoreCase = true) -> "tv"
            else -> "movie"
        }
    }

    private fun parsePlayerOptions(doc: Document): List<PlayerOption> {
        val options = mutableListOf<PlayerOption>()
        doc.select("li.dooplay_player_option").forEach { element ->
            val rawNume = element.attr("data-nume")
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-id") }
            val nume = rawNume.trim().takeIf { it.isNotBlank() } ?: return@forEach
            val type = element.attr("data-type").takeIf { it.isNotBlank() }
            val label = element.attr("data-title").takeIf { it.isNotBlank() }
                ?: element.attr("data-name").takeIf { it.isNotBlank() }
                ?: element.selectFirst(".title")?.text()?.takeIf { it.isNotBlank() }
                ?: element.text().takeIf { it.isNotBlank() }
            options += PlayerOption(nume, type, label)
        }
        return options
    }

    private suspend fun fetchAjaxEmbeds(
        doc: Document,
        pageUrl: String,
        ajaxConfig: AjaxConfig?
    ): List<String> {
        val config = ajaxConfig ?: return emptyList()
        val postId = extractPostId(doc) ?: return emptyList()
        val defaultType = inferPlayerType(doc, pageUrl)
        val declaredOptions = parsePlayerOptions(doc)
        val options = if (declaredOptions.isNotEmpty()) {
            declaredOptions
        } else {
            val fallbackCount = config.classItem?.takeIf { it > 0 } ?: 6
            (1..fallbackCount).map { index ->
                PlayerOption(index.toString(), defaultType, "Source $index")
            }
        }
        if (options.isEmpty()) return emptyList()
        val embeds = mutableListOf<String>()
        val seen = hashSetOf<String>()
        for (option in options) {
            val nume = option.nume
            val type = option.type?.takeIf { it.isNotBlank() } ?: defaultType
            val embed = fetchAjaxEmbed(config, pageUrl, postId, type, nume) ?: continue
            val normalized = normalizeEmbedUrl(pageUrl, embed)
            if (seen.add(normalized)) {
                embeds += normalized
            }
        }
        return embeds
    }

    private suspend fun fetchAjaxEmbed(
        config: AjaxConfig,
        pageUrl: String,
        postId: String,
        type: String,
        nume: String
    ): String? {
        return try {
            val method = config.playMethod?.lowercase(Locale.ROOT)
            val payload = when (method) {
                "wp_json" -> {
                    val api = config.playerApi ?: return null
                    val normalizedApi = if (api.endsWith("/")) api.dropLast(1) else api
                    val target = "$normalizedApi/$postId/$type/$nume"
                    fetchJson(target, referer = pageUrl).text
                }
                else -> {
                    val ajaxUrl = config.url
                    app.post(
                        ajaxUrl,
                        referer = pageUrl,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = buildAjaxHeaders(
                            ajaxUrl,
                            pageUrl,
                            mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            )
                        )
                    ).text
                }
            }
            val json = JSONObject(payload)
            json.optString("embed_url").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeEmbedUrl(pageUrl: String, url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> resolveAgainst(pageUrl, trimmed) ?: trimmed
            else -> trimmed
        }
    }

    private fun extractYearFrom(title: String): Int? {
        val match = yearRegex.find(title) ?: return null
        return match.value.toIntOrNull()
    }

    private fun determineTvType(meta: ProviderMeta?, url: String, element: Element? = null): TvType {
        if (meta?.slug?.contains("anime", ignoreCase = true) == true) {
            return TvType.Anime
        }
        if (element != null) {
            val classes = element.classNames().map { it.lowercase(Locale.ROOT) }
            if (classes.any { it.contains("tvshows") || it.contains("tvshow") || it.contains("season") || it.contains("episode") }) {
                return TvType.TvSeries
            }
        }
        val slug = url.lowercase(Locale.ROOT)
        return when {
            slug.contains("/tvshows/") || slug.contains("/serie") || slug.contains("/episode") || slug.contains("/season") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun createSearchItem(
        meta: ProviderMeta,
        title: String,
        url: String,
        poster: String?,
        includeProvider: Boolean,
        query: String?,
        tvType: TvType = TvType.Movie
    ): SearchItem? {
        rememberSlugForUrl(url, meta.slug)
        val displayTitle = if (includeProvider) {
            "${meta.displayName} - $title"
        } else {
            title
        }
        val resolvedType = when (tvType) {
            TvType.Anime -> TvType.Anime
            TvType.TvSeries -> TvType.TvSeries
            else -> TvType.Movie
        }
        val response = when (resolvedType) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(displayTitle, url, resolvedType)
            else -> newMovieSearchResponse(displayTitle, url, resolvedType)
        }
        if (!poster.isNullOrBlank()) {
            response.posterUrl = poster
        }
        val year = extractYearFrom(title)
        if (year != null && resolvedType == TvType.Movie) {
            (response as? MovieSearchResponse)?.year = year
        }
        val score = computeMatchScore(title, query)
        if (!query.isNullOrBlank() && score <= 0) return null
        return SearchItem(response, score)
    }

    private fun resolveHref(card: Element, rule: Rule, baseUrl: String): String? {
        val parts = rule.urlSel.split("@", limit = 2)
        val cssRaw = parts.getOrNull(0)?.trim()
        if (cssRaw.isNullOrBlank()) return null
        val attr = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "href"
        val element = when {
            cssRaw.equals("self", ignoreCase = true) -> card
            card.`is`(cssRaw) -> card
            else -> card.selectFirst(cssRaw)
        } ?: if (attr.equals("href", ignoreCase = true) && card.tagName().equals("a", ignoreCase = true)) {
            card
        } else {
            null
        } ?: return null
        val attributeCandidates = mutableListOf(attr)
        if (!attr.equals("href", ignoreCase = true)) attributeCandidates += "href"
        attributeCandidates += listOf("data-$attr", "data-href", "data-url", "data-link", "data-src")
        for (candidate in attributeCandidates.distinct()) {
            val raw = element.attr(candidate).takeIf { it.isNotBlank() }
            if (!raw.isNullOrBlank()) {
                val resolved = resolveAgainst(baseUrl, raw)
                if (!resolved.isNullOrBlank()) return resolved
                val absolute = element.absUrl(candidate).takeIf { it.isNotBlank() }
                if (!absolute.isNullOrBlank()) return absolute
                return raw
            }
        }
        val fallback = element.absUrl(attr).takeIf { it.isNotBlank() }
        if (!fallback.isNullOrBlank()) return fallback
        return null
    }

    private fun extractWithRule(
        meta: ProviderMeta,
        doc: Document,
        query: String?,
        dedupe: MutableSet<String>,
        limit: Int,
        includeProvider: Boolean
    ): List<SearchItem> {
        val rule = meta.rule ?: return emptyList()
        val responses = ArrayList<SearchItem>()
        val pageBase = doc.location().ifBlank { meta.baseUrl }
        val cards = doc.select(rule.itemSel)
        for (card in cards) {
            val href = resolveHref(card, rule, pageBase) ?: continue
            val normalizedHref = canonicalizeUrl(href)
            val dedupeKey = "${meta.slug}::$normalizedHref"
            if (!dedupe.add(dedupeKey)) continue
            val title = resolveTitle(card, rule, meta.displayName, query) ?: continue
            if (!query.isNullOrBlank() && !titleMatchesQuery(title, query)) continue
            val poster = extractPoster(card, pageBase)
            val tvType = determineTvType(meta, href, card)
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = href,
                poster = poster,
                includeProvider = includeProvider,
                query = query,
                tvType = tvType
            )
            if (item != null) {
                responses += item
            }
            if (responses.size >= limit) break
        }
        return responses
    }

    private fun extractOneJourHome(
        meta: ProviderMeta,
        doc: Document,
        dedupe: MutableSet<String>
    ): List<HomePageList> {
        val pageBase = doc.location().ifBlank { meta.baseUrl }
        val sections = mutableListOf<HomePageList>()
        for (container in doc.select("div.items")) {
            val articles = container.select("article")
            if (articles.isEmpty()) continue
            val sectionTitle = container.previousElementSibling()
                ?.selectFirst("h2, h3, h1")
                ?.text()
                ?.trim()
                ?.takeUnless { it.isBlank() }
                ?: container.parent()?.selectFirst("h2, h3, h1")
                    ?.text()
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
                ?: meta.displayName
            val responses = mutableListOf<SearchResponse>()
            for (article in articles) {
                val anchor = article.selectFirst("a[href]") ?: continue
                var href = anchor.absUrl("href")
                if (href.isBlank()) {
                    val rawHref = anchor.attr("href")
                    if (rawHref.isBlank()) continue
                    href = resolveAgainst(pageBase, rawHref) ?: continue
                }
                val normalizedHref = canonicalizeUrl(href)
                val dedupeKey = "${meta.slug}::$normalizedHref"
                if (!dedupe.add(dedupeKey)) continue
                val titleText = article.selectFirst("h3 a")?.text()?.trim()?.takeUnless { it.isBlank() }
                    ?: anchor.attr("title").takeIf { it.isNotBlank() }
                    ?: anchor.text().takeIf { it.isNotBlank() }
                    ?: meta.displayName
                val posterElement = article.selectFirst("img")
                val poster = posterElement?.let { img ->
                    sequenceOf("data-src", "data-lazy-src", "data-original", "data-orig-file", "src")
                        .mapNotNull { attr -> img.attr(attr).takeIf { it.isNotBlank() } }
                        .map { resolveAgainst(pageBase, it) ?: it }
                        .firstOrNull()
                }
                val tvType = determineTvType(meta, href, article)
                val item = createSearchItem(
                    meta = meta,
                    title = titleText,
                    url = href,
                    poster = poster,
                    includeProvider = false,
                    query = null,
                    tvType = tvType
                )
                if (item != null) {
                    responses += item.response
                }
                if (responses.size >= 20) break
            }
            if (responses.isNotEmpty()) {
                sections += HomePageList(sectionTitle, responses)
            }
        }
        return sections
    }

    private fun fallbackExtraction(
        meta: ProviderMeta,
        doc: Document,
        query: String?,
        dedupe: MutableSet<String>,
        limit: Int,
        includeProvider: Boolean
    ): List<SearchItem> {
        val responses = ArrayList<SearchItem>()
        val pageBase = doc.location().ifBlank { meta.baseUrl }
        val baseHost = normalizeHost(meta.baseUrl)
        val anchors = doc.select("a[href]")
        for (anchor in anchors) {
            val resolved = anchor.absUrl("href").ifBlank {
                val raw = anchor.attr("href")
                if (raw.isBlank()) "" else resolveAgainst(pageBase, raw) ?: raw
            }
            if (resolved.isBlank()) continue
            val host = normalizeHost(resolved)
            if (baseHost != null && host != null && host != baseHost && !host.endsWith(".$baseHost")) continue
            val rawTitle = anchor.text().ifBlank { anchor.attr("title") }
            val title = sanitizeTitle(rawTitle)
            if (title.isBlank()) continue
            if (!query.isNullOrBlank() && !titleMatchesQuery(title, query)) continue
            val normalizedResolved = canonicalizeUrl(resolved)
            val dedupeKey = "${meta.slug}::$normalizedResolved"
            if (!dedupe.add(dedupeKey)) continue
            val tvType = determineTvType(meta, resolved, anchor)
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = resolved,
                poster = null,
                includeProvider = includeProvider,
                query = query,
                tvType = tvType
            )
            if (item != null) {
                responses += item
            }
            if (responses.size >= limit) break
        }
        return responses
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        if (metas.isEmpty()) return emptyList()
        val dedupe = hashSetOf<String>()
        val results = mutableListOf<SearchItem>()
        for (meta in metas) {
            try {
                val url = buildSearchUrl(meta.baseUrl, meta.rule, query) ?: continue
                val response = fetchHtml(url, referer = meta.baseUrl)
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
        if (results.isEmpty()) {
            val fallback = searchFallbackImdb(query)
            if (fallback.isNotEmpty()) return fallback
            return emptyList()
        }
        val sorted = results.sortedWith(
            compareByDescending<SearchItem> { it.score }
                .thenBy { normalizeSearchText(it.response.name) }
        )
        val primary = sorted.take(60).map { it.response }.toMutableList()
        if (primary.size < 10) {
            val existing = primary.map { it.url }.toHashSet()
            val fallback = searchFallbackImdb(query)
            fallback.forEach { item ->
                if (existing.add(item.url)) {
                    primary += item
                }
            }
        }
        return primary.take(60)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        if (metas.isEmpty()) {
            val fallbackLists = loadHomeFallback()
            if (fallbackLists.isNotEmpty()) {
                return newHomePageResponse(fallbackLists, hasNext = false)
            }
            return newHomePageResponse(emptyList(), hasNext = false)
        }
        val pageSize = 5
        val startIndex = max(0, (page - 1) * pageSize)
        if (startIndex >= metas.size) return newHomePageResponse(emptyList(), hasNext = false)
        val slice = metas.drop(startIndex).take(pageSize)
        val lists = mutableListOf<HomePageList>()
        for (meta in slice) {
            val rule = meta.rule
            try {
                val dedupe = hashSetOf<String>()
                var handled = false
                if (page == 1 && meta.slug.equals("1JOUR1FILM", ignoreCase = true)) {
                    val apiSections = runCatching { fetchOneJourHomeFromApi(meta) }.getOrElse { emptyList() }
                    if (apiSections.isNotEmpty()) {
                        lists.addAll(apiSections)
                        handled = true
                    }
                }
                if (handled) continue
                val effectiveRule = rule ?: continue
                val response = fetchHtml(meta.baseUrl, referer = null)
                val doc = response.document
                val items = extractWithRule(meta, doc, query = null, dedupe = dedupe, limit = 20, includeProvider = false)
                val responses = items.map { it.response }
                if (responses.isNotEmpty()) {
                    lists += HomePageList(meta.displayName, responses)
                } else if (meta.slug.equals("1JOUR1FILM", ignoreCase = true)) {
                    val fallbackSections = extractOneJourHome(meta, doc, dedupe)
                    if (fallbackSections.isNotEmpty()) {
                        lists.addAll(fallbackSections)
                    }
                }
            } catch (_: Throwable) {
                // Ignore providers that fail
            }
        }
        if (page == 1) {
            HomeConfig.ensureLoaded()
            val fallbackLists = loadHomeFallback()
            if (fallbackLists.isNotEmpty()) {
                lists.addAll(fallbackLists)
            }
        }
        if (lists.isEmpty()) {
            HomeConfig.ensureLoaded()
            val fallbackLists = loadHomeFallback()
            if (fallbackLists.isNotEmpty()) {
                return newHomePageResponse(fallbackLists, hasNext = false)
            }
        }
        val hasNext = startIndex + pageSize < metas.size
        return newHomePageResponse(lists, hasNext)
    }

    private data class LoadData(
        val url: String,
        val slug: String?,
        val imdbId: String?,
        val title: String?,
        val poster: String?,
        val year: Int?
    )

    private fun encodeLoadData(
        url: String,
        slug: String?,
        imdbId: String? = null,
        title: String? = null,
        poster: String? = null,
        year: Int? = null
    ): String {
        val obj = JSONObject()
        obj.put("url", url)
        slug?.takeIf { it.isNotBlank() }?.let { obj.put("slug", it) }
        imdbId?.takeIf { it.isNotBlank() }?.let { obj.put("imdbId", it) }
        title?.takeIf { it.isNotBlank() }?.let { obj.put("title", it) }
        poster?.takeIf { it.isNotBlank() }?.let { obj.put("poster", it) }
        year?.let { obj.put("year", it) }
        return obj.toString()
    }

    private fun decodeLoadData(data: String): LoadData {
        return runCatching {
            val obj = JSONObject(data)
            val targetUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: data
            val slug = obj.optString("slug").takeIf { it.isNotBlank() }
            val imdb = obj.optString("imdbId").takeIf { it.isNotBlank() }
            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val poster = obj.optString("poster").takeIf { it.isNotBlank() }
            val yearValue = obj.optInt("year")
            val year = if (obj.has("year") && yearValue > 0) yearValue else null
            LoadData(targetUrl, slug, imdb, title, poster, year)
        }.getOrElse {
            LoadData(data, null, null, null, null, null)
        }
    }

    private fun sanitizeHtmlText(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return Jsoup.parse(html).text().trim().takeUnless { it.isBlank() }
    }

    private fun extractDescriptionFromDoc(doc: Document): String? {
        val selectors = listOf(
            "div.wp-content p",
            "div.content p",
            "div.post-content p",
            "div.description p",
            "div.entry-content p",
            "section#info p",
            "div#info p",
            "article p"
        )
        for (selector in selectors) {
            val text = doc.select(selector)
                .mapNotNull { it.text().trim().takeIf { value -> value.isNotBlank() } }
                .firstOrNull()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        val metaOg = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return metaOg?.trim()?.takeUnless { it.isBlank() }
    }

    private fun detectWordpressType(pageUrl: String): String? {
        val lower = pageUrl.lowercase(Locale.ROOT)
        return when {
            lower.contains("/films/") || lower.contains("/movies/") -> "movies"
            lower.contains("/tvshows/") || lower.contains("/series/") -> "tvshows"
            lower.contains("/seasons/") -> "seasons"
            lower.contains("/episodes/") -> "episodes"
            else -> null
        }
    }

    private fun resolveApiBase(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(url)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        }.getOrNull()
    }

    private suspend fun fetchWordpressDescription(pageUrl: String, baseUrl: String?): String? {
        val type = detectWordpressType(pageUrl) ?: return null
        val apiBase = resolveApiBase(baseUrl ?: pageUrl) ?: return null
        val slug = runCatching {
            val path = URI(pageUrl).path.trim('/')
            path.substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val apiUrl = "$apiBase/wp-json/wp/v2/$type?slug=$slug&_embed=1"
        return runCatching {
            val response = fetchJson(apiUrl, referer = apiBase)
            val array = JSONArray(response.text)
            val first = array.optJSONObject(0) ?: return@runCatching null
            val description = sanitizeHtmlText(first.optJSONObject("content")?.optString("rendered"))
            description
        }.getOrNull()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            if (url.startsWith("imdb://")) {
                val imdbId = url.removePrefix("imdb://")
                val item = getCachedImdbItem(imdbId) ?: fetchImdbDetails(imdbId) ?: return null
                val dataPayload = encodeLoadData(
                    url = url,
                    slug = null,
                    imdbId = imdbId,
                    title = item.title,
                    poster = item.poster,
                    year = item.year
                )
                return newMovieLoadResponse(item.title, url, TvType.Movie, dataUrl = dataPayload) { }.apply {
                    item.poster?.let { posterUrl = it }
                    item.year?.let { year = it }
                }
            }
            ensureRemoteConfigs()
            val metas = gatherProviders()
            val cachedSlug = findSlugForUrl(url)
            val meta = when {
                !cachedSlug.isNullOrBlank() -> findMetaBySlug(metas, cachedSlug)
                else -> findMetaByUrl(url, metas)
            }
            if (meta != null) {
                rememberSlugForUrl(url, meta.slug)
            }
            val referer = meta?.baseUrl ?: url
            val response = fetchHtml(url, referer = referer)
            val doc = response.document
            val description = extractDescriptionFromDoc(doc)
                ?: fetchWordpressDescription(url, meta?.baseUrl ?: url)
            val title = doc.selectFirst("title")?.text()?.trim()?.ifBlank { null } ?: meta?.displayName ?: name
            val dataPayload = encodeLoadData(url, meta?.slug)
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl = dataPayload) {
                description?.let { plot = it }
            }
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
            val loadData = decodeLoadData(data)
            val pageUrl = loadData.url
            val imdbId = loadData.imdbId
            if (!imdbId.isNullOrBlank()) {
                val embedUrl = "https://vidsrc.net/embed/movie?imdb=$imdbId"
                return runCatching {
                    loadExtractor(embedUrl, "https://vidsrc.net/", subtitleCallback, callback)
                    true
                }.getOrElse { false }
            }
            ensureRemoteConfigs()
            val metas = gatherProviders()
            val cachedSlug = loadData.slug ?: findSlugForUrl(pageUrl)
            val meta = when {
                !cachedSlug.isNullOrBlank() -> findMetaBySlug(metas, cachedSlug)
                else -> findMetaByUrl(pageUrl, metas)
            }
            if (meta != null) {
                rememberSlugForUrl(pageUrl, meta.slug)
            }
            val referer = meta?.baseUrl ?: pageUrl
            val response = fetchHtml(pageUrl, referer = referer)
            val doc = response.document
            val ajaxConfig = parseAjaxConfig(doc, pageUrl)
            val ajaxEmbeds = fetchAjaxEmbeds(doc, pageUrl, ajaxConfig)
            val candidates = linkedSetOf<String>()
            ajaxEmbeds.forEach { candidates += it }
            candidates += gatherEmbedCandidates(doc, pageUrl, meta)
            if (candidates.isEmpty()) {
                candidates += gatherFallbackCandidates(doc, pageUrl)
            }
            val ajaxRequests = gatherAjaxRequests(doc, pageUrl, meta)
            if (ajaxRequests.isNotEmpty()) {
                ajaxRequests.forEach { request ->
                    try {
                        val ajaxResponse = fetchHtml(request.url, referer = request.referer)
                        if (meta != null) {
                            rememberSlugForUrl(request.url, meta.slug)
                        }
                        val ajaxDoc = ajaxResponse.document
                        candidates += gatherEmbedCandidates(ajaxDoc, request.url, meta)
                        if (candidates.size < 40) {
                            candidates += gatherFallbackCandidates(ajaxDoc, request.url)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            val prioritizedCandidates = linkedSetOf<String>()
            candidates.forEach { candidate ->
                val expanded = expandEmbedSources(candidate, pageUrl)
                if (expanded.isNotEmpty()) {
                    expanded.forEach { prioritizedCandidates += it }
                } else {
                    prioritizedCandidates += candidate
                }
            }
            var success = false
            prioritizedCandidates.take(25).forEach { link ->
                try {
                    loadExtractor(link, pageUrl, subtitleCallback, callback)
                    success = true
                } catch (_: Throwable) {
                }
            }
            success
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun expandEmbedSources(embedUrl: String, referer: String): List<String> {
        val lowered = embedUrl.lowercase(Locale.ROOT)
        if (lowered.contains("api.voirfilm.cam") || (lowered.contains("voirfilm.") && lowered.contains("/film/"))) {
            return runCatching {
                val response = fetchHtml(embedUrl, referer = referer)
                val doc = response.document
                doc.select(".embed .servers .content li[data-url]")
                    .mapNotNull { element ->
                        val raw = element.attr("data-url").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val resolved = resolveAgainst(embedUrl, raw) ?: raw
                        if (resolved.startsWith("http", ignoreCase = true)) resolved else null
                    }
                    .distinct()
            }.getOrElse { emptyList() }
        }
        return emptyList()
    }

    private suspend fun fetchWpCollection(
        meta: ProviderMeta,
        apiBase: String,
        type: String,
        perPage: Int,
        tvType: TvType
    ): List<SearchResponse> {
        return runCatching {
            val url = "${apiBase.trimEnd('/')}/wp-json/wp/v2/$type?per_page=$perPage&_embed=1"
            val response = fetchJson(url, referer = apiBase)
            val array = JSONArray(response.text)
            val seen = hashSetOf<String>()
            val results = mutableListOf<SearchResponse>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val link = obj.optString("link").takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(link)) continue
                val titleHtml = obj.optJSONObject("title")?.optString("rendered")
                val title = sanitizeHtmlText(titleHtml) ?: continue
                val embedded = obj.optJSONObject("_embedded")
                val poster = embedded
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url")
                    ?.takeIf { it.isNotBlank() }
                val item = createSearchItem(
                    meta = meta,
                    title = title,
                    url = link,
                    poster = poster,
                    includeProvider = false,
                    query = null,
                    tvType = tvType
                ) ?: continue
                results += item.response
                if (results.size >= perPage) break
            }
            results
        }.getOrElse { emptyList() }
    }

    private suspend fun fetchOneJourHomeFromApi(meta: ProviderMeta): List<HomePageList> {
        val apiBase = resolveApiBase(meta.baseUrl) ?: return emptyList()
        val movies = fetchWpCollection(meta, apiBase, "movies", 20, TvType.Movie)
        val shows = fetchWpCollection(meta, apiBase, "tvshows", 20, TvType.TvSeries)
        val seasons = fetchWpCollection(meta, apiBase, "seasons", 20, TvType.TvSeries)
        val episodes = fetchWpCollection(meta, apiBase, "episodes", 20, TvType.TvSeries)

        val sections = mutableListOf<HomePageList>()

        val popular = mutableListOf<SearchResponse>()
        movies.take(10).forEach { if (popular.size < 20) popular += it }
        shows.take(10).forEach { if (popular.size < 20) popular += it }
        if (popular.isNotEmpty()) {
            sections += HomePageList("Films/Series Populaires", popular)
        }
        if (movies.isNotEmpty()) {
            sections += HomePageList("Derniers films", movies)
        }
        if (shows.isNotEmpty()) {
            sections += HomePageList("Dernieres series", shows)
        }
        if (seasons.isNotEmpty()) {
            sections += HomePageList("Dernieres saisons", seasons)
        }
        if (episodes.isNotEmpty()) {
            sections += HomePageList("Derniers episodes", episodes)
        }
        return sections
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
