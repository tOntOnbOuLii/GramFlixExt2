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
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import com.gramflix.extensions.config.HomeConfig
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private data class SearchItem(
        val response: SearchResponse,
        val score: Int
    )

    private data class AjaxRequest(
        val url: String,
        val referer: String
    )

    private data class ImdbItem(
        val imdbId: String,
        val title: String,
        val poster: String?,
        val year: Int?
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

    private fun extractYearFrom(title: String): Int? {
        val match = yearRegex.find(title) ?: return null
        return match.value.toIntOrNull()
    }

    private fun createSearchItem(
        meta: ProviderMeta,
        title: String,
        url: String,
        poster: String?,
        includeProvider: Boolean,
        query: String?
    ): SearchItem? {
        rememberSlugForUrl(url, meta.slug)
        val displayTitle = if (includeProvider) {
            "${meta.displayName} - $title"
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
            val item = createSearchItem(meta, title, href, poster, includeProvider, query)
            if (item != null) {
                responses += item
            }
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
            val item = createSearchItem(meta, title, resolved, null, includeProvider, query)
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
                val responses = items.map { it.response }
                if (responses.isNotEmpty()) {
                    lists += HomePageList(meta.displayName, responses)
                }
            } catch (_: Throwable) {
                // Ignore providers that fail
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
            val doc = app.get(url, referer = referer).document
            val title = doc.selectFirst("title")?.text()?.trim()?.ifBlank { null } ?: meta?.displayName ?: name
            val dataPayload = encodeLoadData(url, meta?.slug)
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl = dataPayload) { }
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
            val response = app.get(pageUrl, referer = referer)
            val doc = response.document
            val candidates = linkedSetOf<String>()
            candidates += gatherEmbedCandidates(doc, pageUrl, meta)
            if (candidates.isEmpty()) {
                candidates += gatherFallbackCandidates(doc, pageUrl)
            }
            val ajaxRequests = gatherAjaxRequests(doc, pageUrl, meta)
            if (ajaxRequests.isNotEmpty()) {
                ajaxRequests.forEach { request ->
                    try {
                        val ajaxResponse = app.get(request.url, referer = request.referer)
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
            var success = false
            candidates.take(25).forEach { link ->
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
