package com.gramflix.extensions.providers

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import com.gramflix.extensions.config.HomeConfig
import com.gramflix.extensions.config.HostersConfig
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.text.Normalizer
import java.util.ArrayList
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ConfigDrivenProvider(
    private val forcedSlug: String? = null,
    private val forcedDisplayName: String? = null
) : MainAPI() {
    private val forcedSlugLower = forcedSlug?.lowercase(Locale.ROOT)
    private val gfSuffix = "GF"

    override var name = forcedDisplayName?.let { ensureGFSuffix(it) } ?: "GramFlix Stream GF"
    override var mainUrl = "https://webpanel.invalid"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)
    // Home réactivée (mais filtrée) pour exposer FrenchStream sur l'accueil
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() {
            ensureRemoteConfigs()
            HomeConfig.ensureLoaded()
            val metas = gatherProviders().filter { it.showOnHome }
            val entries = metas.map { meta ->
                MainPageData(meta.displayName, meta.slug, horizontalImages = false)
            }
            val fallbackAvailable = (HomeConfig.sectionsArray()?.length() ?: 0) > 0
            return when {
                entries.isEmpty() && fallbackAvailable -> listOf(MainPageData("Fallback IMDB", FALLBACK_HOME_KEY, false))
                fallbackAvailable -> entries + MainPageData("Fallback IMDB", FALLBACK_HOME_KEY, false)
                else -> entries
            }
        }

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
        val rule: Rule?,
        val showOnHome: Boolean = true
    )

    private data class HosterPattern(
        val slug: String,
        val displayName: String,
        val regexes: List<Regex>
    )

    private data class SearchItem(
        val response: SearchResponse,
        val score: Int
    )

    private data class HomeCacheEntry(
        val timestamp: Long,
        val sections: List<HomePageList>
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

    private data class NebryxEntry(
        val type: String,
        val tmdbId: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    private data class NebryxApiLinks(
        val tmdb: String?,
        val imdb: String?,
        val link1: String?,
        val link2: String?,
        val link3: String?,
        val link4: String?,
        val link5: String?,
        val link6: String?,
        val link7: String?,
        val link1vostfr: String?,
        val link2vostfr: String?,
        val link3vostfr: String?,
        val link4vostfr: String?,
        val link5vostfr: String?,
        val link6vostfr: String?,
        val link7vostfr: String?,
        val link1vo: String?,
        val link2vo: String?,
        val link3vo: String?,
        val link4vo: String?,
        val link5vo: String?,
        val link6vo: String?,
        val link7vo: String?
    )

    private val homePrioritySlugs = listOf("frenchstream", "wiflix")

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        private const val ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val ACCEPT_JSON = "application/json, text/plain, */*"
        private const val ACCEPT_AJAX = "application/json, text/javascript, */*;q=0.01"
        private const val ACCEPT_LANGUAGE = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val FALLBACK_HOME_KEY = "__fallback__"
        private val UNS_AES_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val UNS_AES_IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        private const val NEBRYX_SLUG = "nebryx"
        private const val NEBRYX_SCHEME = "nebryx://"
        private const val COFLIX_SLUG = "coflix"
        private const val COFLIX_SCHEME = "coflix://"
        private const val DEFAULT_COFLIX_BASE = "https://coflix.si"
        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_DEFAULT_LANGUAGE = "fr-FR"
        private const val DEFAULT_NEBRYX_BASE = "https://nebryx.fr"
        private const val FREMBED_SLUG = "frembed"
        private const val DEFAULT_FREMBED_BASE = "https://frembed.my"
        private const val FRENCH_TV_LIVE_SLUG = "frenchtvlive"
        private const val FRENCH_TV_SOURCE = "FrenchTVLive"
        private const val HOME_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val HOME_CACHE_MAX_ENTRIES = 32
        private const val TMDB_API_KEY = "660883a8a688af69b7e1d834f864e006"
        private const val TMDB_CACHE_TTL_MS = 10 * 60 * 1000L

        private fun logNebryx(message: String) {
            println("[Nebryx] $message")
        }
    }

    private fun ensureGFSuffix(label: String): String {
        val trimmed = label.trim()
        return if (trimmed.lowercase(Locale.ROOT).endsWith(gfSuffix.lowercase(Locale.ROOT))) {
            trimmed
        } else {
            "$trimmed$gfSuffix"
        }
    }

    private fun isSlugAllowed(slug: String): Boolean {
        return forcedSlugLower == null || slug.lowercase(Locale.ROOT) == forcedSlugLower
    }

    private fun maybeUpdateIdentity(meta: ProviderMeta?) {
        if (meta == null) return
        name = ensureGFSuffix(meta.displayName)
        mainUrl = meta.baseUrl.ifBlank { mainUrl }
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

    private fun hexToBytes(input: String): ByteArray? {
        val clean = input.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching {
            val result = ByteArray(clean.length / 2)
            var index = 0
            while (index < clean.length) {
                val byte = clean.substring(index, index + 2).toInt(16)
                result[index / 2] = byte.toByte()
                index += 2
            }
            result
        }.getOrNull()
    }

    private fun decryptUnsPayload(payload: String): JSONObject? {
        val bytes = hexToBytes(payload) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val key = SecretKeySpec(UNS_AES_KEY, "AES")
            val iv = IvParameterSpec(UNS_AES_IV)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decrypted = cipher.doFinal(bytes)
            val text = decrypted.toString(Charsets.UTF_8)
            JSONObject(text)
        }.getOrNull()
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
    private val seasonLabelRegex = Regex("(?i)(season|saison)\\s*(\\d+)")
    private val seasonEpisodeComboRegex =
        Regex("(?i)(?:s|season|saison)\\s*(\\d+)[^\\d]+(?:e|ep|episode)?\\s*(\\d+)")
    private val dualNumberRegex = Regex("(\\d+)\\s*[-x]\\s*(\\d+)")
    private val digitsRegex = Regex("\\d+")
    private val urlSlugLock = Any()
    private val urlSlugCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 1024
    }
    private val fallbackCache = object : LinkedHashMap<String, ImdbItem>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImdbItem>?): Boolean = size > 256
    }
    private val homeCacheLock = Any()
    private val homeCache = object : LinkedHashMap<String, HomeCacheEntry>(HOME_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HomeCacheEntry>?): Boolean =
            size > HOME_CACHE_MAX_ENTRIES
    }
    private val tmdbCacheLock = Any()
    private val tmdbCache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?): Boolean = size > 96
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

    private fun getCachedHomeLists(slug: String): List<HomePageList>? {
        synchronized(homeCacheLock) {
            val entry = homeCache[slug] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > HOME_CACHE_TTL_MS) {
                homeCache.remove(slug)
                return null
            }
            return entry.sections
        }
    }

    private fun cacheHomeLists(slug: String, sections: List<HomePageList>) {
        if (sections.isEmpty()) return
        synchronized(homeCacheLock) {
            homeCache[slug] = HomeCacheEntry(System.currentTimeMillis(), sections)
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

    private suspend fun tmdbGet(
        path: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject? {
        if (TMDB_API_KEY.isBlank()) return null
        val query = LinkedHashMap<String, String>()
        query["api_key"] = TMDB_API_KEY
        query["language"] = TMDB_DEFAULT_LANGUAGE
        for ((key, value) in params) {
            if (value.isNotBlank()) {
                query[key] = value
            }
        }
        val queryString = query.entries.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            "$encodedKey=$encodedValue"
        }
        val url = buildString {
            append(TMDB_API_BASE)
            append(path)
            if (queryString.isNotBlank()) {
                append("?")
                append(queryString)
            }
        }
        synchronized(tmdbCacheLock) {
            tmdbCache[url]?.let { (ts, body) ->
                if (System.currentTimeMillis() - ts <= TMDB_CACHE_TTL_MS) {
                    return body
                }
                tmdbCache.remove(url)
            }
        }
        val response = runCatching { fetchJson(url) }.getOrNull() ?: return null
        val body = response.text ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            synchronized(tmdbCacheLock) {
                tmdbCache[url] = System.currentTimeMillis() to json
            }
        }
        return json
    }

    private fun buildNebryxPoster(path: String?): String? {
        val clean = path?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return "$TMDB_IMAGE_BASE$clean"
    }

    private fun tmdbReleaseYear(value: String?): Int? {
        if (value.isNullOrBlank() || value.length < 4) return null
        return value.substring(0, 4).toIntOrNull()
    }

    private fun parseIsoDateToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val date = LocalDate.parse(value.trim())
            date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun buildNebryxResponses(
        meta: ProviderMeta,
        array: JSONArray?,
        limit: Int,
        includeProvider: Boolean,
        type: String,
        tvType: TvType
    ): List<SearchResponse> {
        if (array == null || array.length() == 0) return emptyList()
        val responses = ArrayList<SearchResponse>()
        val maxItems = min(limit, array.length())
        val titleKey = if (type.equals("tv", ignoreCase = true)) "name" else "title"
        val dateKey = if (type.equals("tv", ignoreCase = true)) "first_air_date" else "release_date"
        for (index in 0 until maxItems) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optInt("id")
            if (id <= 0) continue
            val title = obj.optString(titleKey).takeIf { it.isNotBlank() } ?: continue
            val poster = buildNebryxPoster(obj.optString("poster_path"))
            val url = buildNebryxUrl(type, id)
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = url,
                poster = poster,
                includeProvider = includeProvider,
                query = null,
                tvType = tvType
            ) ?: continue
            val year = tmdbReleaseYear(obj.optString(dateKey))
            if (year != null && tvType == TvType.Movie) {
                (item.response as? MovieSearchResponse)?.year = year
            }
            responses += item.response
        }
        return responses
    }

    private fun buildCoflixResponses(
        meta: ProviderMeta,
        array: JSONArray?,
        limit: Int,
        includeProvider: Boolean,
        type: String,
        tvType: TvType
    ): List<SearchResponse> {
        if (array == null || array.length() == 0) return emptyList()
        val responses = ArrayList<SearchResponse>()
        val maxItems = min(limit, array.length())
        val titleKey = if (type.equals("tv", ignoreCase = true)) "name" else "title"
        val dateKey = if (type.equals("tv", ignoreCase = true)) "first_air_date" else "release_date"
        for (index in 0 until maxItems) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optInt("id")
            if (id <= 0) continue
            val title = obj.optString(titleKey).takeIf { it.isNotBlank() } ?: continue
            val poster = buildNebryxPoster(obj.optString("poster_path"))
            val url = buildCoflixUrl(type, id)
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = url,
                poster = poster,
                includeProvider = includeProvider,
                query = null,
                tvType = tvType
            ) ?: continue
            val year = tmdbReleaseYear(obj.optString(dateKey))
            if (year != null && tvType == TvType.Movie) {
                (item.response as? MovieSearchResponse)?.year = year
            }
            responses += item.response
        }
        return responses
    }

    private suspend fun searchNebryx(meta: ProviderMeta, query: String): List<SearchItem> {
        if (query.isBlank()) return emptyList()
        val json = tmdbGet("/search/multi", mapOf("query" to query, "page" to "1")) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val items = mutableListOf<SearchItem>()
        val seen = hashSetOf<String>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val id = obj.optInt("id")
            if (id <= 0) continue
            val mediaType = obj.optString("media_type").lowercase(Locale.ROOT)
            val titleKey: String
            val dateKey: String
            val tvType: TvType
            val typeSlug: String
            when (mediaType) {
                "movie" -> {
                    titleKey = "title"
                    dateKey = "release_date"
                    tvType = TvType.Movie
                    typeSlug = "movie"
                }
                "tv" -> {
                    titleKey = "name"
                    dateKey = "first_air_date"
                    tvType = TvType.TvSeries
                    typeSlug = "tv"
                }
                else -> continue
            }
            val title = obj.optString(titleKey).takeIf { it.isNotBlank() } ?: continue
            val poster = buildNebryxPoster(obj.optString("poster_path"))
            val url = buildNebryxUrl(typeSlug, id)
            if (!seen.add("$typeSlug-$id")) continue
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = url,
                poster = poster,
                includeProvider = true,
                query = query,
                tvType = tvType
            ) ?: continue
            if (tvType == TvType.Movie) {
                val year = tmdbReleaseYear(obj.optString(dateKey))
                if (year != null) {
                    (item.response as? MovieSearchResponse)?.year = year
                }
            }
            items += item
        }
        return items
    }

    private fun parseCoflixPoster(html: String?): String? {
        val clean = html?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val doc = Jsoup.parse(clean)
        val src = doc.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: return null
        return if (src.startsWith("//")) "https:$src" else src
    }

    private fun coflixApiBase(): String = "${coflixBaseUrl().trimEnd('/')}/wp-json/apiflix/v1"

    private fun mapCoflixType(type: String?): TvType = when (type?.lowercase(Locale.ROOT)) {
        "series", "doramas", "animes" -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun nebryxApiBase(): String = "${frembedBaseUrl().trimEnd('/')}/api/films"

    private fun buildCoflixSearchItems(meta: ProviderMeta, array: JSONArray?): List<SearchItem> {
        if (array == null || array.length() == 0) return emptyList()
        val items = mutableListOf<SearchItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            val poster = parseCoflixPoster(obj.optString("image"))
            val tvType = mapCoflixType(obj.optString("post_type"))
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = resolveAgainst(coflixBaseUrl(), url) ?: url,
                poster = poster,
                includeProvider = true,
                query = null,
                tvType = tvType
            ) ?: continue
            items += item
        }
        return items
    }

    private suspend fun searchCoflix(meta: ProviderMeta, query: String): List<SearchItem> {
        if (query.isBlank()) return emptyList()
        val encoded = try {
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            query
        }
        val url = "${coflixBaseUrl().trimEnd('/')}/suggest.php?query=$encoded"
        val text = runCatching { app.get(url, referer = coflixBaseUrl()).text }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildCoflixSearchItems(meta, array)
    }

    private suspend fun fetchNebryxHome(meta: ProviderMeta): List<HomePageList> {
        val sections = mutableListOf<HomePageList>()
        val endpoints = listOf(
            Triple("Films populaires", "/movie/popular", TvType.Movie),
            Triple("Top films", "/movie/top_rated", TvType.Movie),
            Triple("Sorties a venir", "/movie/upcoming", TvType.Movie),
            Triple("Series populaires", "/tv/popular", TvType.TvSeries),
            Triple("Top series", "/tv/top_rated", TvType.TvSeries)
        )
        for ((label, path, tvType) in endpoints) {
            val json = tmdbGet(path) ?: continue
            val typeSlug = if (path.contains("/tv/")) "tv" else "movie"
            val entries = buildNebryxResponses(
                meta = meta,
                array = json.optJSONArray("results"),
                limit = 20,
                includeProvider = false,
                type = typeSlug,
                tvType = tvType
            )
            if (entries.isNotEmpty()) {
                sections += HomePageList("${meta.displayName} - $label", entries)
            }
        }
        return sections
    }

    private suspend fun fetchFrenchTvHome(meta: ProviderMeta): List<HomePageList> {
        val sections = mutableListOf<HomePageList>()
        val dedupe = hashSetOf<String>()
        val response = fetchHtml(meta.baseUrl, referer = null)
        val doc = response.document
        for (sect in doc.select("div.sect")) {
            val sectionTitle = sect.selectFirst(".st-left .st-capt")
                ?.text()
                ?.trim()
                ?.ifBlank { null }
                ?: meta.displayName
            val items = mutableListOf<SearchResponse>()
            for (card in sect.select("div.short")) {
                val anchor = card.selectFirst("a.short-poster[href]") ?: continue
                val rawHref = anchor.attr("href")
                val href = resolveAgainst(meta.baseUrl, rawHref) ?: anchor.absUrl("href")
                if (href.isBlank()) continue
                val normalizedHref = canonicalizeUrl(href)
                if (!dedupe.add(normalizedHref)) continue
                val title = card.selectFirst(".short-title")
                    ?.text()
                    ?.trim()
                    ?.ifBlank { null }
                    ?: anchor.attr("alt").takeIf { it.isNotBlank() }
                    ?: anchor.text().takeIf { it.isNotBlank() }
                    ?: meta.displayName
                val poster = card.selectFirst("img")
                    ?.let { img ->
                        sequenceOf("data-src", "data-original", "src")
                            .mapNotNull { attr -> img.attr(attr).takeIf { it.isNotBlank() } }
                            .firstOrNull()
                    }
                    ?.let { resolveAgainst(meta.baseUrl, it) ?: it }
                val item = createSearchItem(
                    meta = meta,
                    title = title,
                    url = normalizedHref,
                    poster = poster,
                    includeProvider = false,
                    query = null,
                    tvType = TvType.TvSeries
                ) ?: continue
                items += item.response
            }
            if (items.isNotEmpty()) {
                sections += HomePageList("${meta.displayName} - $sectionTitle", items)
            }
        }
        return sections
    }

    private fun extractFirstMediaUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.firstOrNull {
            it.contains(".m3u8", ignoreCase = true) || it.contains(".mpd", ignoreCase = true) || it.contains(".mp4", ignoreCase = true)
        }
    }

    private suspend fun loadFrenchTvPlayer(
        pageUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = referer ?: pageUrl
        val visited = hashSetOf<String>()

        fun normalizeMediaUrl(raw: String): String {
            if (raw.startsWith("//")) return "https:${raw.removePrefix("//")}"
            return raw
        }

        suspend fun emit(url: String, ref: String) {
            val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(
                    source = FRENCH_TV_SOURCE,
                    name = FRENCH_TV_SOURCE,
                    url = url,
                    type = type
                ) {
                    this.referer = ref
                    quality = Qualities.Unknown.value
                }
            )
        }

        fun extractProtocolRelative(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val rel = Regex("""["'](//[^"'\\s]+\\.(?:m3u8|mpd|mp4)[^"'\\s]*)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)
            return rel?.let { "https:${it.removePrefix("//")}" }
        }

        fun extractBase64Media(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val regex = Regex("""[A-Za-z0-9+/=]{40,}""")
            for (match in regex.findAll(text)) {
                val decoded = runCatching { String(Base64.getDecoder().decode(match.value)) }.getOrNull() ?: continue
                val url = extractFirstMediaUrl(decoded) ?: extractProtocolRelative(decoded)
                if (!url.isNullOrBlank()) return url
            }
            return null
        }

        fun pickMedia(html: String, doc: Document): String? {
            extractFirstMediaUrl(html)?.let { return it }
            extractProtocolRelative(html)?.let { return it }
            extractBase64Media(html)?.let { return it }
            doc.selectFirst("video source[src]")?.absUrl("src")?.ifBlank { null }?.let { return it }
            doc.selectFirst("video[src]")?.absUrl("src")?.ifBlank { null }?.let { return it }
            doc.select("a[href]").firstOrNull { link ->
                val href = link.absUrl("href")
                href.contains(".m3u8", true) || href.contains(".mp4", true) || href.contains(".mpd", true)
            }?.absUrl("href")?.ifBlank { null }?.let { return it }
            return null
        }

        suspend fun crawl(url: String, ref: String?, depth: Int = 0): Boolean {
            if (depth > 3) return false
            val normalized = canonicalizeUrl(url)
            if (!visited.add(normalized)) return false
            val effectiveRef = ref ?: url
            val response = runCatching { fetchHtml(url, referer = effectiveRef) }.getOrNull() ?: return false
            val html = response.text
            val doc = response.document
            val media = pickMedia(html, doc)
            if (!media.isNullOrBlank()) {
                emit(normalizeMediaUrl(media), effectiveRef)
                return true
            }
            val nextCandidates = linkedSetOf<String>()
            doc.select("iframe[src], video[src], source[src]").forEach { element ->
                val raw = element.absUrl("src").ifBlank { element.attr("src") }
                val resolved = resolveAgainst(url, raw)?.takeIf { it.isNotBlank() } ?: raw
                if (resolved.startsWith("http", ignoreCase = true)) {
                    nextCandidates += resolved
                }
            }
            doc.select("a[href]").forEach { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                if (href.contains(".m3u8", true) || href.contains(".mp4", true) || href.contains(".mpd", true)) {
                    val resolved = resolveAgainst(url, href) ?: href
                    if (resolved.startsWith("http", ignoreCase = true)) {
                        nextCandidates += resolved
                    }
                }
            }
            for (candidate in nextCandidates) {
                if (crawl(candidate, url, depth + 1)) return true
                val extracted = runCatching {
                    loadExtractor(candidate, url, subtitleCallback, callback)
                    true
                }.getOrElse { false }
                if (extracted) return true
            }
            return runCatching {
                loadExtractor(url, effectiveRef, subtitleCallback, callback)
                true
            }.getOrElse { false }
        }

        return crawl(pageUrl, base)
    }

    private fun buildCoflixHomeItems(meta: ProviderMeta, array: org.json.JSONArray?, tvType: TvType): List<SearchResponse> {
        if (array == null || array.length() == 0) return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            val poster = parseCoflixPoster(obj.optString("path"))
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = resolveAgainst(coflixBaseUrl(), url) ?: url,
                poster = poster,
                includeProvider = false,
                query = null,
                tvType = tvType
            ) ?: continue
            results += item.response
        }
        return results
    }

    private suspend fun fetchCoflixHome(meta: ProviderMeta): List<HomePageList> {
        val apiBase = coflixApiBase()
        val sections = mutableListOf<HomePageList>()
        val endpoints = listOf(
            "movies" to "Films",
            "series" to "Series",
            "doramas" to "Doramas",
            "animes" to "Animes"
        )
        for ((type, label) in endpoints) {
            val url = "$apiBase/options/?years=&post_type=$type&genres=&page=1&sort=1"
            val json = runCatching { org.json.JSONObject(app.get(url, referer = coflixBaseUrl()).text) }.getOrNull() ?: continue
            val items = buildCoflixHomeItems(meta, json.optJSONArray("results"), mapCoflixType(type))
            if (items.isNotEmpty()) {
                sections += HomePageList("${meta.displayName} - $label", items)
            }
        }
        return sections
    }

    private suspend fun buildTmdbEpisodes(
        tmdbId: Int,
        seasons: JSONArray?,
        slug: String,
        urlBuilder: (Int, Int) -> String
    ): List<Episode> {
        if (seasons == null || seasons.length() == 0) return emptyList()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until seasons.length()) {
            val seasonObj = seasons.optJSONObject(i) ?: continue
            val seasonNumber = seasonObj.optInt("season_number")
            if (seasonNumber <= 0) continue
            val seasonJson = tmdbGet("/tv/$tmdbId/season/$seasonNumber") ?: continue
            val eps = seasonJson.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val episodeObj = eps.optJSONObject(j) ?: continue
                val episodeNumber = episodeObj.optInt("episode_number")
                if (episodeNumber <= 0) continue
                val epTitle = episodeObj.optString("name").takeIf { it.isNotBlank() } ?: "Episode $episodeNumber"
                val epUrl = urlBuilder(seasonNumber, episodeNumber)
                val encoded = encodeLoadData(epUrl, slug)
                val still = buildNebryxPoster(episodeObj.optString("still_path"))
                val overview = episodeObj.optString("overview").takeIf { it.isNotBlank() }
                val runtime = episodeObj.optInt("runtime").takeIf { it > 0 }
                val airDate = parseIsoDateToMillis(episodeObj.optString("air_date"))
                episodes += newEpisode(encoded) {
                    name = epTitle
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = still
                    description = overview
                    date = airDate
                    runTime = runtime
                }
            }
        }
        return episodes
    }

    private suspend fun loadNebryx(url: String): LoadResponse? {
        val entry = parseNebryxUrl(url) ?: return null
        return when (entry.type) {
            "movie" -> loadNebryxMovie(entry)
            "tv" -> loadNebryxSeries(entry)
            else -> null
        }
    }

    private suspend fun loadCoflix(url: String): LoadResponse? {
        val entry = parseCoflixUrl(url) ?: return null
        return when (entry.type) {
            "movie" -> loadCoflixMovie(entry)
            "tv" -> loadCoflixSeries(entry)
            else -> null
        }
    }

    private suspend fun loadNebryxMovie(entry: NebryxEntry): LoadResponse? {
        val tmdbId = entry.tmdbId
        val json = tmdbGet("/movie/$tmdbId") ?: return null
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: json.optString("name").takeIf { it.isNotBlank() } ?: "Nebryx"
        val overview = json.optString("overview").takeIf { it.isNotBlank() }
        val poster = buildNebryxPoster(json.optString("poster_path"))
        val year = tmdbReleaseYear(json.optString("release_date"))
        val imdbId = json.optString("imdb_id").takeIf { it.isNotBlank() }
        val canonicalUrl = buildNebryxUrl("movie", tmdbId)
        val dataPayload = encodeLoadData(
            url = canonicalUrl,
            slug = NEBRYX_SLUG,
            imdbId = imdbId,
            title = title,
            poster = poster,
            year = year
        )
        return newMovieLoadResponse(title, canonicalUrl, TvType.Movie, dataUrl = dataPayload) {
            overview?.let { plot = it }
            poster?.let { posterUrl = it }
            year?.let { this.year = it }
        }
    }

    private suspend fun loadCoflixMovie(entry: NebryxEntry): LoadResponse? {
        val tmdbId = entry.tmdbId
        val json = tmdbGet("/movie/$tmdbId") ?: return null
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: json.optString("name").takeIf { it.isNotBlank() } ?: "Coflix"
        val overview = json.optString("overview").takeIf { it.isNotBlank() }
        val poster = buildNebryxPoster(json.optString("poster_path"))
        val year = tmdbReleaseYear(json.optString("release_date"))
        val imdbId = json.optString("imdb_id").takeIf { it.isNotBlank() }
        val canonicalUrl = buildCoflixUrl("movie", tmdbId)
        val dataPayload = encodeLoadData(
            url = canonicalUrl,
            slug = COFLIX_SLUG,
            imdbId = imdbId,
            title = title,
            poster = poster,
            year = year
        )
        return newMovieLoadResponse(title, canonicalUrl, TvType.Movie, dataUrl = dataPayload) {
            overview?.let { plot = it }
            poster?.let { posterUrl = it }
            year?.let { this.year = it }
        }
    }

    private suspend fun loadNebryxSeries(entry: NebryxEntry): LoadResponse? {
        val tmdbId = entry.tmdbId
        val json = tmdbGet("/tv/$tmdbId") ?: return null
        val title = json.optString("name").takeIf { it.isNotBlank() }
            ?: json.optString("original_name").takeIf { it.isNotBlank() }
            ?: "Nebryx"
        val overview = json.optString("overview").takeIf { it.isNotBlank() }
        val poster = buildNebryxPoster(json.optString("poster_path"))
        val year = tmdbReleaseYear(json.optString("first_air_date"))
        val episodes = buildTmdbEpisodes(tmdbId, json.optJSONArray("seasons"), NEBRYX_SLUG) { season, episode ->
            buildNebryxUrl("tv", tmdbId, season, episode)
        }
        if (episodes.isEmpty()) return null
        val canonicalUrl = buildNebryxUrl("tv", tmdbId)
        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
            overview?.let { plot = it }
            poster?.let { posterUrl = it }
            year?.let { this.year = it }
        }
    }

    private suspend fun loadCoflixSeries(entry: NebryxEntry): LoadResponse? {
        val tmdbId = entry.tmdbId
        val json = tmdbGet("/tv/$tmdbId") ?: return null
        val title = json.optString("name").takeIf { it.isNotBlank() }
            ?: json.optString("original_name").takeIf { it.isNotBlank() }
            ?: "Coflix"
        val overview = json.optString("overview").takeIf { it.isNotBlank() }
        val poster = buildNebryxPoster(json.optString("poster_path"))
        val year = tmdbReleaseYear(json.optString("first_air_date"))
        val episodes = buildTmdbEpisodes(tmdbId, json.optJSONArray("seasons"), COFLIX_SLUG) { season, episode ->
            buildCoflixUrl("tv", tmdbId, season, episode)
        }
        if (episodes.isEmpty()) return null
        val canonicalUrl = buildCoflixUrl("tv", tmdbId)
        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
            overview?.let { plot = it }
            poster?.let { posterUrl = it }
            year?.let { this.year = it }
        }
    }

    private suspend fun loadCoflixPage(pageUrl: String): LoadResponse? {
        val doc = fetchHtml(pageUrl, referer = coflixBaseUrl()).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBeforeLast("En")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()
            ?: "Coflix"
        val type = if (pageUrl.contains("/series", ignoreCase = true) || doc.select("section.sc-seasons ul li input").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        var poster = doc.selectFirst("img.TPostBg")?.attr("src")?.takeIf { it.isNotBlank() }
        if (poster.isNullOrBlank()) {
            poster = parseCoflixPoster(doc.select("div.title-img img").toString())
        }
        val description = doc.selectFirst("div.summary.link-co p")?.text()?.takeIf { it.isNotBlank() }
        val imdbUrl = doc.selectFirst("p.dtls a:contains(IMDb)")?.attr("href")
        val tmdbId = doc.selectFirst("p.dtls a:contains(TMDb)")?.attr("href")?.substringAfterLast("/")?.toIntOrNull()
        val tags = doc.select("div.meta.df.aic.fww a").map { it.text() }
        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            val apiBase = coflixApiBase()
            doc.select("section.sc-seasons ul li input").forEach { input ->
                val seasonNumber = input.attr("data-season").toIntOrNull() ?: return@forEach
                val postId = input.attr("post-id").takeIf { it.isNotBlank() } ?: return@forEach
                val apiUrl = "$apiBase/series/$postId/$seasonNumber"
                val json = runCatching { JSONObject(app.get(apiUrl, referer = pageUrl).text) }.getOrNull()
                    ?: return@forEach
                val eps = json.optJSONArray("episodes") ?: return@forEach
                for (i in 0 until eps.length()) {
                    val ep = eps.optJSONObject(i) ?: continue
                    val epTitle = ep.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val epNumber = ep.optString("number").toIntOrNull()
                    val epSeason = ep.optString("season").toIntOrNull() ?: seasonNumber
                    val epUrl = ep.optString("links").takeIf { it.isNotBlank() } ?: continue
                    val epPoster = parseCoflixPoster(ep.optString("image"))
                    val encoded = encodeLoadData(epUrl, COFLIX_SLUG)
                    episodes += newEpisode(encoded) {
                        name = epTitle
                        season = epSeason
                        episode = epNumber
                        posterUrl = epPoster
                    }
                }
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                poster?.let { posterUrl = it }
                description?.let { plot = it }
                tags.takeIf { it.isNotEmpty() }?.let { this.tags = it }
            }
        }
        val dataPayload = encodeLoadData(pageUrl, COFLIX_SLUG, imdbId = imdbUrl, title = title, poster = poster)
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, dataUrl = dataPayload) {
            poster?.let { posterUrl = it }
            description?.let { plot = it }
            tags.takeIf { it.isNotEmpty() }?.let { this.tags = it }
        }
    }

    private suspend fun loadNebryxLinks(
        data: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entry = parseNebryxUrl(data.url) ?: return false
        logNebryx("loadNebryxLinks type=${entry.type} tmdb=${entry.tmdbId} season=${entry.season} episode=${entry.episode}")
        val pageReferer = data.url.takeIf { it.isNotBlank() } ?: nebryxBaseUrl()
        val embedBase = frembedBaseUrl()
        var emitted = false
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }
        val countingSubtitle: (SubtitleFile) -> Unit = { sub ->
            emitted = true
            subtitleCallback(sub)
        }
        suspend fun handleChristopher(url: String, referer: String?) {
            val page = runCatching { fetchHtml(url, referer = referer ?: pageReferer) }.getOrNull() ?: return
            val body = page.text
            val m3u8 = Regex("""https?://[^"'\\s]+\\.m3u8[^"'\\s]*""", RegexOption.IGNORE_CASE).find(body)?.value
            val fileUrl = m3u8 ?: Regex("""source\s*=\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.getOrNull(1)
            val finalUrl = fileUrl ?: return
            callback(
                newExtractorLink(
                    source = "ChristopherUntilPoint",
                    name = "ChristopherUntilPoint",
                    url = finalUrl,
                    type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: url
                    headers = mapOf(
                        "Referer" to "https://christopheruntilpoint.com/",
                        "Origin" to "https://christopheruntilpoint.com",
                        "User-Agent" to BROWSER_USER_AGENT
                    )
                    quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }
        suspend fun handleStreamTales(url: String, referer: String?) {
            val decoded: String = try {
                URLDecoder.decode(url, StandardCharsets.UTF_8.name())
            } catch (_: Throwable) {
                url
            }
            val direct: String? = try {
                Regex("""url=([^&]+)""").find(decoded)?.groupValues?.getOrNull(1)?.let { candidate ->
                    try {
                        URLDecoder.decode(candidate, StandardCharsets.UTF_8.name())
                    } catch (_: Throwable) {
                        candidate
                    }
                }
            } catch (_: Throwable) {
                null
            } ?: decoded.takeIf { it.contains(".mp4", ignoreCase = true) || it.contains(".m3u8", ignoreCase = true) }
            val finalUrl: String = direct ?: return
            val type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(
                    source = "StreamTales",
                    name = "StreamTales",
                    url = finalUrl,
                    type = type
                ) {
                    this.referer = referer ?: url
                    quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }
        suspend fun tryLoadWithReferers(link: String, referers: List<String>) {
            val refs: List<String> = referers.filter { it.isNotBlank() }.distinct()
            if (refs.isEmpty()) {
                runCatching { loadExtractor(link, link, countingSubtitle, countingCallback) }
                return
            }
            refs.forEach { ref ->
                val before = emitted
                runCatching { loadExtractor(link, ref, countingSubtitle, countingCallback) }
                if (emitted && emitted != before) return
            }
        }
        suspend fun emitFromApiLinks(api: NebryxApiLinks?, apiReferer: String?) {
            if (api == null) return
            val candidates = listOfNotNull(
                api.link1, api.link2, api.link3, api.link4, api.link5, api.link6, api.link7,
                api.link1vostfr, api.link2vostfr, api.link3vostfr, api.link4vostfr, api.link5vostfr, api.link6vostfr, api.link7vostfr,
                api.link1vo, api.link2vo, api.link3vo, api.link4vo, api.link5vo, api.link6vo, api.link7vo
            ).mapNotNull { it.trim().takeIf { url -> url.isNotBlank() } }.distinct()
            val referers: List<String> = buildList<String> {
                apiReferer?.let { add(it) }
                pageReferer.takeUnless { it == apiReferer }?.let { add(it) }
                data.url.takeUnless { it.isBlank() }?.let { add(it) }
                frembedBaseUrl().takeIf { it.isNotBlank() }?.let { add(it) }
                nebryxBaseUrl().takeIf { it.isNotBlank() }?.let { add(it) }
            }
            candidates.forEach { link ->
                when {
                    link.contains("streamtales", ignoreCase = true) -> {
                        runCatching { handleStreamTales(link, referers.firstOrNull()) }
                    }
                    link.contains("christopheruntilpoint.com", ignoreCase = true) -> {
                        runCatching { handleChristopher(link, referers.firstOrNull()) }
                    }
                    else -> {
                        val refs = (referers + link).distinct()
                        runCatching { tryLoadWithReferers(link, refs) }
                        if (!emitted) {
                            // dernier essai avec referer Frembed generique pour bypass 403 (netu/uqload/dsvplay)
                            runCatching { loadExtractor(link, frembedBaseUrl(), countingSubtitle, countingCallback) }
                        }
                    }
                }
            }
        }
        return when (entry.type) {
            "movie" -> {
                val embedUrl = "$embedBase/api/film.php?id=${entry.tmdbId}"
                logNebryx("movie warm=${embedUrl} referer=${pageReferer}")
                emitFromApiLinks(runCatching { fetchNebryxApiLinks(entry.tmdbId, "movie", warmUrl = embedUrl) }.getOrNull(), embedUrl)
                val playerUrl = resolveFrembedPlayerUrl(embedUrl, pageReferer)
                logNebryx("movie playerUrl=${playerUrl}")
                val okPrimary = runCatching {
                    loadExtractor(playerUrl, embedUrl, countingSubtitle, countingCallback)
                    emitted
                }.getOrElse { false }
                val okFallback = if (!okPrimary) {
                    runCatching {
                        loadExtractor(embedUrl, pageReferer, countingSubtitle, countingCallback)
                        emitted
                    }.getOrElse { false }
                } else okPrimary
                okPrimary || okFallback || emitted
            }
            "tv" -> {
                val season = entry.season ?: return false
                val episode = entry.episode ?: return false
                val embedUrl = "$embedBase/api/serie.php?id=${entry.tmdbId}&sa=$season&epi=$episode"
                logNebryx("tv warm=${embedUrl} referer=${pageReferer}")
                emitFromApiLinks(runCatching { fetchNebryxApiLinks(entry.tmdbId, "tv", warmUrl = embedUrl) }.getOrNull(), embedUrl)
                val playerUrl = resolveFrembedPlayerUrl(embedUrl, pageReferer)
                logNebryx("tv playerUrl=${playerUrl}")
                val okPrimary = runCatching {
                    loadExtractor(playerUrl, embedUrl, countingSubtitle, countingCallback)
                    emitted
                }.getOrElse { false }
                val okFallback = if (!okPrimary) {
                    runCatching {
                        loadExtractor(embedUrl, pageReferer, countingSubtitle, countingCallback)
                        emitted
                    }.getOrElse { false }
                } else okPrimary
                okPrimary || okFallback || emitted
            }
            else -> false
        }
    }

    private suspend fun loadNebryxEmbed(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchHtml(pageUrl, referer = nebryxBaseUrl()).document
        val iframe = doc.selectFirst("iframe[src]")?.absUrl("src")?.ifBlank { null }
            ?: doc.selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolveAgainst(pageUrl, it) }
            ?: return false
        return runCatching {
            loadExtractor(iframe, pageUrl, subtitleCallback, callback)
            true
        }.getOrDefault(false)
    }

    private suspend fun loadCoflixEmbed(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchHtml(pageUrl, referer = coflixBaseUrl()).document
        val iframe = doc.selectFirst("div.embed iframe")?.absUrl("src")?.ifBlank { null }
            ?: doc.selectFirst("div.embed iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolveAgainst(pageUrl, it) }
            ?: return false
        val embedDoc = fetchHtml(iframe, referer = pageUrl).document
        val options = embedDoc.select("div.OptionsLangDisp li[onclick]")
        var success = false
        options.forEach { li ->
            val onclick = li.attr("onclick")
            val encoded = onclick.substringAfter("showVideo('", "").substringBefore("'", "")
            val decoded = decodeBase64Url(encoded)?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@forEach
            runCatching {
                loadExtractor(decoded, iframe, subtitleCallback, callback)
                success = true
            }
        }
        return success
    }

    private suspend fun loadCoflixLinks(
        entry: NebryxEntry,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = entry.tmdbId
        val imdb = resolveImdbFromNebryx(entry)
        return when (entry.type.lowercase(Locale.ROOT)) {
            "movie" -> {
                val embed = when {
                    !imdb.isNullOrBlank() -> "https://vidsrc.net/embed/movie?imdb=$imdb"
                    else -> "https://vidsrc.net/embed/movie?tmdb=$tmdbId"
                }
                runCatching {
                    loadExtractor(embed, "https://vidsrc.net/", subtitleCallback, callback)
                    true
                }.getOrElse { false }
            }
            "tv" -> {
                val season = entry.season ?: return false
                val episode = entry.episode ?: return false
                val embed = when {
                    !imdb.isNullOrBlank() -> "https://vidsrc.net/embed/tv/$imdb/$season/$episode"
                    else -> "https://vidsrc.net/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
                }
                runCatching {
                    loadExtractor(embed, "https://vidsrc.net/", subtitleCallback, callback)
                    true
                }.getOrElse { false }
            }
            else -> false
        }
    }

    private suspend fun resolveImdbFromNebryx(entry: NebryxEntry): String? {
        val path = when (entry.type.lowercase(Locale.ROOT)) {
            "tv" -> "/tv/${entry.tmdbId}"
            else -> "/movie/${entry.tmdbId}"
        }
        val json = tmdbGet(path) ?: return null
        return json.optString("imdb_id").takeIf { it.isNotBlank() }
    }

    private suspend fun resolveFrembedPlayerUrl(embedUrl: String, referer: String): String {
        return runCatching {
            val response = fetchHtml(embedUrl, referer = referer)
            val iframe = response.document.selectFirst("iframe#player, iframe[src]")
            val resolved = iframe?.absUrl("src")?.takeIf { it.isNotBlank() }
            resolved ?: embedUrl
        }.getOrDefault(embedUrl)
    }

    private fun providerPriority(slug: String): Int {
        val idx = homePrioritySlugs.indexOfFirst { it.equals(slug, ignoreCase = true) }
        return if (idx >= 0) idx else homePrioritySlugs.size
    }

    private fun gatherProviders(): List<ProviderMeta> {
        val list = mutableListOf<ProviderMeta>()
        val providers = RemoteConfig.providersObject()
        if (providers != null) {
            val iterator = providers.keys()
            while (iterator.hasNext()) {
                val slug = iterator.next()
                if (!isSlugAllowed(slug)) continue
                val info = providers.optJSONObject(slug) ?: continue
                val baseUrl = info.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
                val displayName = ensureGFSuffix(info.optString("name").takeIf { it.isNotBlank() } ?: slug)
                val showOnHome = if (forcedSlugLower != null) true else info.optBoolean("showOnHome", true)
                list += ProviderMeta(
                    slug = slug,
                    displayName = displayName,
                    baseUrl = baseUrl,
                    rule = parseRule(slug),
                    showOnHome = showOnHome
                )
            }
        }
        // Force critical providers in case remote config is missing/invalid.
        fun missing(slug: String) = list.none { it.slug.equals(slug, ignoreCase = true) }
        fun addFallback(slug: String, displayName: String, baseUrl: String) {
            if (!isSlugAllowed(slug) || !missing(slug)) return
            list += ProviderMeta(
                slug = slug,
                displayName = ensureGFSuffix(displayName),
                baseUrl = baseUrl,
                rule = parseRule(slug),
                showOnHome = true
            )
        }
        addFallback("FrenchStream", "French stream", "https://french-stream.one")
        addFallback("Flemmix", "Flemmix", "https://flemmix.club")
        if (forcedSlugLower != null && list.isEmpty() && !forcedSlug.isNullOrBlank()) {
            list += ProviderMeta(
                slug = forcedSlug,
                displayName = ensureGFSuffix(forcedDisplayName ?: forcedSlug),
                baseUrl = "https://webpanel.invalid",
                rule = parseRule(forcedSlug),
                showOnHome = true
            )
        }
        if (forcedSlugLower != null) {
            maybeUpdateIdentity(list.firstOrNull())
        }
        return list.sortedWith(
            compareBy<ProviderMeta> { providerPriority(it.slug) }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun isNebryx(meta: ProviderMeta?): Boolean =
        meta?.slug?.equals(NEBRYX_SLUG, ignoreCase = true) == true

    private fun isNebryxSlug(slug: String?): Boolean =
        slug?.equals(NEBRYX_SLUG, ignoreCase = true) == true

    private fun isAnimeSama(meta: ProviderMeta?): Boolean =
        meta?.slug?.equals("AnimeSama", ignoreCase = true) == true

    private fun isAnimeSamaUrl(url: String?): Boolean =
        url?.contains("anime-sama.org", ignoreCase = true) == true

    private fun isFrenchTv(meta: ProviderMeta?): Boolean =
        meta?.slug?.equals(FRENCH_TV_LIVE_SLUG, ignoreCase = true) == true

    private fun nebryxBaseUrl(): String {
        val candidate = RemoteConfig
            .getProviderBaseUrlOrNull(NEBRYX_SLUG, DEFAULT_NEBRYX_BASE)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NEBRYX_BASE
        val sanitized = candidate.lowercase(Locale.ROOT)
        return if (sanitized.contains("webpanel.invalid") || sanitized.startsWith("nebryx://")) {
            DEFAULT_NEBRYX_BASE
        } else {
            candidate
        }
    }

    private fun frembedBaseUrl(): String =
        RemoteConfig.getProviderBaseUrlOrNull(FREMBED_SLUG, DEFAULT_FREMBED_BASE)?.trimEnd('/') ?: DEFAULT_FREMBED_BASE

    private fun isCoflix(meta: ProviderMeta?): Boolean =
        meta?.slug?.equals(COFLIX_SLUG, ignoreCase = true) == true

    private fun coflixBaseUrl(): String =
        RemoteConfig.getProviderBaseUrlOrNull(COFLIX_SLUG, DEFAULT_COFLIX_BASE)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_COFLIX_BASE

    private fun isNebryxUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (parseNebryxUrl(url) != null) return true
        val host = normalizeHost(url)
        val nebryxHost = normalizeHost(nebryxBaseUrl())
        return host != null && nebryxHost != null && (host == nebryxHost || host.endsWith(".$nebryxHost"))
    }

    private fun isCoflixUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (parseCoflixUrl(url) != null) return true
        val host = normalizeHost(url)
        val coflixBase = RemoteConfig.getProviderBaseUrlOrNull(COFLIX_SLUG, "https://coflix.day") ?: "https://coflix.day"
        val coflixHost = normalizeHost(coflixBase)
        return host != null && coflixHost != null && (host == coflixHost || host.endsWith(".$coflixHost"))
    }

    private fun buildNebryxUrl(type: String, tmdbId: Int, season: Int? = null, episode: Int? = null): String {
        val base = nebryxBaseUrl().trimEnd('/')
        val queryParts = mutableListOf(
            "id=$tmdbId",
            "type=${type.lowercase(Locale.ROOT)}"
        )
        if (season != null) queryParts += "season=$season"
        if (episode != null) queryParts += "episode=$episode"
        return "$base/watch.html?${queryParts.joinToString("&")}"
    }

    private fun buildCoflixUrl(type: String, tmdbId: Int, season: Int? = null, episode: Int? = null): String {
        val path = when (type.lowercase(Locale.ROOT)) {
            "tv" -> "tv/$tmdbId"
            else -> "movie/$tmdbId"
        }
        val queryParts = mutableListOf<String>()
        season?.let { queryParts += "season=$it" }
        episode?.let { queryParts += "episode=$it" }
        val query = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""
        return "$COFLIX_SCHEME$path$query"
    }

    private fun parseNebryxUrl(url: String): NebryxEntry? {
        return parseNebryxSchemeUrl(url) ?: parseNebryxWatchUrl(url)
    }

    private fun parseCoflixUrl(url: String): NebryxEntry? {
        if (!url.startsWith(COFLIX_SCHEME, ignoreCase = true)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val payload = uri.schemeSpecificPart?.removePrefix("//") ?: uri.schemeSpecificPart ?: return null
        val trimmed = payload.trim('/').takeIf { it.isNotBlank() } ?: return null
        val parts = trimmed.split("?", limit = 2)
        val path = parts.first()
        val pathParts = path.split("/")
        if (pathParts.size != 2) return null
        val type = pathParts[0].lowercase(Locale.ROOT)
        val id = pathParts[1].toIntOrNull() ?: return null
        val params = parseQueryParams(parts.getOrNull(1).orEmpty())
        val (season, episode) = extractSeasonEpisodeFromParams(params)
        return NebryxEntry(type, id, season, episode)
    }

    private fun parseNebryxSchemeUrl(url: String): NebryxEntry? {
        if (!url.startsWith(NEBRYX_SCHEME, ignoreCase = true)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val payload = uri.schemeSpecificPart?.removePrefix("//") ?: uri.schemeSpecificPart ?: return null
        val trimmed = payload.trim('/').takeIf { it.isNotBlank() } ?: return null
        val parts = trimmed.split("?")
        val path = parts.first()
        val pathParts = path.split("/")
        if (pathParts.size != 2) return null
        val type = pathParts[0].lowercase(Locale.ROOT)
        val id = pathParts[1].toIntOrNull() ?: return null
        val (season, episode) = extractSeasonEpisodeFromParams(parseQueryParams(uri.rawQuery.orEmpty()))
        return NebryxEntry(type, id, season, episode)
    }

    private fun parseNebryxWatchUrl(url: String): NebryxEntry? {
        val normalizedUrl = url.trim()
        val baseUrl = nebryxBaseUrl().trimEnd('/')
        val normalizedBase = baseUrl.lowercase(Locale.ROOT)
        val lowerUrl = normalizedUrl.lowercase(Locale.ROOT)
        if (!lowerUrl.startsWith(normalizedBase)) return null
        val suffix = normalizedUrl.substring(baseUrl.length).removePrefix("/")
        if (suffix.isBlank()) return null
        val parts = suffix.split("?", limit = 2)
        val path = parts.first().lowercase(Locale.ROOT)
        if (path != "watch.html" && path != "watch") return null
        val query = parts.getOrNull(1).orEmpty()
        if (query.isBlank()) return null
        val params = parseQueryParams(query)
        val id = params["id"]?.toIntOrNull() ?: return null
        val type = params["type"]?.takeIf { it.isNotBlank() } ?: return null
        val (season, episode) = extractSeasonEpisodeFromParams(params)
        return NebryxEntry(type, id, season, episode)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { token ->
            val parts = token.split("=", limit = 2)
            val key = parts.getOrNull(0)?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = parts.getOrNull(1) ?: ""
            key to value
        }.toMap()
    }

    private fun decodeBase64Url(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull()
    }

    private fun nebryxLinkEndpoints(tmdbId: Int, type: String): List<String> {
        val base = nebryxApiBase()
        val urls = linkedSetOf<String>()
        urls += "$base?id=$tmdbId&idType=tmdb"
        urls += "$base?id=$tmdbId&idType=$type"
        urls += "$base?id=$tmdbId"
        return urls.toList()
    }

    private suspend fun fetchNebryxApiLinks(tmdbId: Int, type: String, warmUrl: String? = null): NebryxApiLinks? {
        warmUrl?.let {
            logNebryx("warmup GET $it")
            runCatching {
                val warmRes = app.get(it, referer = nebryxBaseUrl())
                val warmCode = runCatching { warmRes.code }.getOrNull()
                logNebryx("warmup status=${warmCode ?: "?"} len=${warmRes.text?.length ?: 0}")
            }.onFailure { err ->
                logNebryx("warmup failed: ${err.message}")
            }
        }
        val endpoints = nebryxLinkEndpoints(tmdbId, type)
        for (url in endpoints) {
            logNebryx("api/films try $url")
            val response = runCatching {
                app.get(
                    url,
                    referer = warmUrl ?: nebryxBaseUrl(),
                    headers = mapOf(
                        "Accept" to ACCEPT_JSON,
                        "Origin" to frembedBaseUrl(),
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to BROWSER_USER_AGENT
                    )
                )
            }.onFailure { err ->
                logNebryx("api/films error on $url : ${err.message}")
            }.getOrNull() ?: continue
            val status = runCatching { response.code }.getOrNull()
            val body = response.text ?: continue
            logNebryx("api/films status=${status ?: "?"} len=${body.length} url=$url")
            val json = runCatching { JSONObject(body) }.onFailure { err ->
                logNebryx("api/films json fail on $url : ${err.message}")
            }.getOrNull() ?: continue
            return NebryxApiLinks(
                tmdb = json.optString("tmdb"),
                imdb = json.optString("imdb"),
                link1 = json.optString("link1"),
                link2 = json.optString("link2"),
                link3 = json.optString("link3"),
                link4 = json.optString("link4"),
                link5 = json.optString("link5"),
                link6 = json.optString("link6"),
                link7 = json.optString("link7"),
                link1vostfr = json.optString("link1vostfr"),
                link2vostfr = json.optString("link2vostfr"),
                link3vostfr = json.optString("link3vostfr"),
                link4vostfr = json.optString("link4vostfr"),
                link5vostfr = json.optString("link5vostfr"),
                link6vostfr = json.optString("link6vostfr"),
                link7vostfr = json.optString("link7vostfr"),
                link1vo = json.optString("link1vo"),
                link2vo = json.optString("link2vo"),
                link3vo = json.optString("link3vo"),
                link4vo = json.optString("link4vo"),
                link5vo = json.optString("link5vo"),
                link6vo = json.optString("link6vo"),
                link7vo = json.optString("link7vo")
            )
        }
        logNebryx("api/films no links tmdb=$tmdbId type=$type")
        return null
    }

    private fun extractSeasonEpisodeFromParams(params: Map<String, String>): Pair<Int?, Int?> {
        var season: Int? = null
        var episode: Int? = null
        for ((key, value) in params) {
            val parsed = value.toIntOrNull()
            when (key) {
                "season", "sa" -> season = parsed
                "episode", "ep", "epi" -> episode = parsed
            }
        }
        return season to episode
    }

    private fun normalizeHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()
    }

    private fun buildHosterPatterns(): List<HosterPattern> {
        val hosters = HostersConfig.hostersObject() ?: return emptyList()
        val results = mutableListOf<HosterPattern>()
        val iterator = hosters.keys()
        while (iterator.hasNext()) {
            val slug = iterator.next()
            val entry = hosters.optJSONObject(slug) ?: continue
            val displayName = entry.optString("name").takeIf { it.isNotBlank() } ?: slug
            val raw = entry.optString("url").takeIf { it.isNotBlank() } ?: continue
            val regexes = raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { compileHosterPattern(it) }
            if (regexes.isNotEmpty()) {
                results += HosterPattern(slug, displayName, regexes)
            }
        }
        return results
    }

    private fun compileHosterPattern(pattern: String): Regex? {
        var token = pattern.trim()
        if (token.isEmpty()) return null
        token = token
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("//")
        token = token.substringBefore("/")
        if (token.isBlank()) return null
        val normalized = token.lowercase(Locale.ROOT)
        val escaped = Regex.escape(normalized).replace("\\*".toRegex(), ".*")
        return escaped.takeIf { it.isNotBlank() }?.let { Regex("^$it$") }
    }

    private fun detectHoster(url: String?, hosterPatterns: List<HosterPattern>): HosterPattern? {
        if (url.isNullOrBlank()) return null
        val host = normalizeHost(url) ?: return null
        val lowered = host.lowercase(Locale.ROOT)
        return hosterPatterns.firstOrNull { entry ->
            entry.regexes.any { regex -> regex.matches(lowered) }
        }
    }

    private fun wrapCallbackWithHosters(
        hosterPatterns: List<HosterPattern>,
        original: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit {
        if (hosterPatterns.isEmpty()) return original
        return { link ->
            val detected = detectHoster(link.url, hosterPatterns)
                ?: detectHoster(link.referer, hosterPatterns)
            if (detected != null) {
                val label = detected.displayName
                original(relabeledLink(link, label))
            } else {
                original(link)
            }
        }
    }

    private fun relabeledLink(link: ExtractorLink, label: String): ExtractorLink {
        val headers = link.headers ?: emptyMap()
        val extractorData = link.extractorData
        val referer = link.referer
        val quality = link.quality
        return ExtractorLink(
            label,
            label,
            link.url,
            referer,
            quality,
            headers,
            extractorData,
            link.type
        )
    }

    private suspend fun fetchAnimeSamaArrays(pageUrl: String, doc: Document? = null): Map<Int, List<String>>? {
        val document = doc ?: fetchHtml(pageUrl, referer = pageUrl).document
        val script = document.selectFirst("script[src*=\"episodes.js\"]") ?: return null
        val rawSrc = script.attr("src").takeIf { it.isNotBlank() } ?: return null
        val scriptUrl = script.absUrl("src").takeIf { it.isNotBlank() } ?: resolveAgainst(pageUrl, rawSrc) ?: rawSrc
        val js = runCatching { app.get(scriptUrl, referer = pageUrl).text }
            .recoverCatching { app.get(scriptUrl, referer = null).text }
            .getOrNull() ?: return null
        val arrays = mutableMapOf<Int, List<String>>()
        val regex = Regex("""var\s+(eps\d+)\s*=\s*\[(.*?)];""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        regex.findAll(js).forEach { match ->
            val name = match.groupValues.getOrNull(1) ?: return@forEach
            val readerIndex = name.removePrefix("eps").toIntOrNull() ?: return@forEach
            val body = match.groupValues.getOrNull(2) ?: return@forEach
            val links = Regex("""['"]([^'"]+)['"]""")
                .findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (links.isNotEmpty()) arrays[readerIndex] = links
        }
        if (arrays.isEmpty()) return null
        if (arrays.containsKey(1) && arrays.containsKey(2)) {
            val first = arrays[1]
            val second = arrays[2]
            if (first != null && second != null) {
                arrays[1] = second
                arrays[2] = first
            }
        }
        return arrays
    }

    private fun buildAnimeSamaEpisodes(
        meta: ProviderMeta,
        pageUrl: String,
        arrays: Map<Int, List<String>>,
        title: String,
        poster: String?
    ): List<Episode> {
        val count = arrays[1]?.size ?: arrays.values.maxOfOrNull { it.size } ?: 0
        if (count <= 0) return emptyList()
        val episodes = mutableListOf<Episode>()
        for (index in 0 until count) {
            val dataPayload = encodeLoadData(
                url = pageUrl,
                slug = meta.slug,
                title = title,
                poster = poster,
                episode = index
            )
            episodes += newEpisode(dataPayload) {
                name = "Episode ${index + 1}"
                season = 1
                episode = index + 1
                posterUrl = poster
            }
        }
        return episodes
    }

    private suspend fun loadAnimeSamaLinks(
        pageUrl: String,
        episodeIndex: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val arrays = fetchAnimeSamaArrays(pageUrl) ?: return false
        val dedupe = hashSetOf<String>()
        var success = false
        arrays.toSortedMap().values.forEach { list ->
            val link = list.getOrNull(episodeIndex) ?: list.getOrNull(0) ?: return@forEach
            if (!dedupe.add(link)) return@forEach
            runCatching {
                loadExtractor(link, pageUrl, subtitleCallback, callback)
                success = true
            }
        }
        return success
    }

    private fun parseAnimeSamaPanels(doc: Document, baseUrl: String): List<Pair<String, String>> {
        val regex = Regex("""panneauAnime\("([^"]+)",\s*"([^"]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val panels = mutableListOf<Pair<String, String>>()
        val seen = hashSetOf<String>()
        val html = doc.outerHtml()
        regex.findAll(html).forEach { match ->
            val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val rawUrl = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (name.isBlank() || rawUrl.isBlank()) return@forEach
            val variants = linkedSetOf(rawUrl)
            fun addVariant(from: String, to: String) {
                if (rawUrl.contains(from, ignoreCase = true)) {
                    variants += rawUrl.replace(from, to, ignoreCase = true)
                }
            }
            addVariant("/vostfr/", "/vf/")
            addVariant("/vostfr/", "/vo/")
            addVariant("/vf/", "/vostfr/")
            addVariant("/vf/", "/vo/")
            addVariant("/vo/", "/vostfr/")
            variants.forEach { variant ->
                val resolved = resolveAgainst(baseUrl, variant) ?: variant
                if (seen.add(resolved)) {
                    val suffix = when {
                        variant.contains("/vf/", ignoreCase = true) -> " VF"
                        variant.contains("/vo/", ignoreCase = true) -> " VO"
                        variant.contains("/vostfr/", ignoreCase = true) -> " VOSTFR"
                        else -> ""
                    }
                    panels += name + suffix to resolved
                }
            }
        }
        if (panels.isEmpty()) {
            // Fallback panels (common patterns when scripts are missing)
            val normalizedBase = baseUrl.trim().trimEnd('/') + "/"
            val defaults = listOf(
                "Saison 1 VOSTFR" to "saison1/vostfr/",
                "Saison 1 VF" to "saison1/vf/",
                "Saison 1 VO" to "saison1/vo/",
                "Film VOSTFR" to "film/vostfr/",
                "Film VF" to "film/vf/",
                "Film VO" to "film/vo/"
            )
            defaults.forEach { (name, path) ->
                val resolved = resolveAgainst(normalizedBase, path) ?: (normalizedBase + path)
                if (seen.add(resolved)) {
                    panels += name to resolved
                }
            }
        }
        return panels
    }

    private suspend fun fetchAnimeSamaEpisodeLinks(streamPage: String): List<List<String>> {
        val request = runCatching { app.get(streamPage) }.getOrNull() ?: return emptyList()
        if (!request.isSuccessful) return emptyList()
        val doc = request.document
        val scriptContainer = doc.selectFirst("#sousBlocMiddle script")?.toString() ?: ""
        val episodeKeyRegex = Regex("""<script[^>]*src=['"]([^'"]*episodes\.js\?filever=\d+)['"][^>]*>""")
        val episodeKey = episodeKeyRegex.find(scriptContainer)?.groupValues?.getOrNull(1) ?: return emptyList()
        val episodesUrl = resolveAgainst(streamPage, episodeKey) ?: "${streamPage.trimEnd('/')}/$episodeKey"
        val js = runCatching { app.get(episodesUrl, referer = streamPage).text }
            .recoverCatching { app.get(episodesUrl).text }
            .getOrNull()
            ?: return emptyList()
        // Parse all eps arrays to keep episode order intact.
        val regex = Regex("""var\s+eps\d+\s*=\s*\[(.*?)];""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val arrays = mutableListOf<List<String>>()
        regex.findAll(js).forEach { match ->
            val body = match.groupValues.getOrNull(1) ?: return@forEach
            val links = Regex("""['"]([^'"]+)['"]""")
                .findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (links.isNotEmpty()) arrays += links
        }
        if (arrays.isEmpty()) return emptyList()
        val maxCount = arrays.maxOfOrNull { it.size } ?: 0
        val episodes = mutableListOf<List<String>>()
        for (i in 0 until maxCount) {
            val perEpisode = mutableListOf<String>()
            arrays.forEach { list ->
                list.getOrNull(i)?.let { perEpisode += it }
            }
            episodes += perEpisode
        }
        return episodes
    }

    private suspend fun fetchAnimeSamaHome(meta: ProviderMeta): List<HomePageList> {
        val doc = fetchHtml(meta.baseUrl, referer = null).document
        val sections = listOf(
            "Derniers épisodes ajoutés" to "#containerAjoutsAnimes a",
            "Derniers contenus sortis" to "#containerSorties a",
            "Les classiques" to "#containerClassiques a",
            "Découvrez des pépites" to "#containerPepites a"
        )
        val dedupe = hashSetOf<String>()
        val homeLists = mutableListOf<HomePageList>()
        sections.forEach { (name, selector) ->
            val items = doc.select(selector).mapNotNull { anchor ->
                val rawHref = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val resolved = resolveAgainst(meta.baseUrl, rawHref) ?: rawHref
                if (!dedupe.add(resolved)) return@mapNotNull null
                val poster = anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                var title = anchor.selectFirst("h1")?.text().orEmpty()
                if (title.isBlank()) title = anchor.selectFirst("h3")?.text().orEmpty()
                val item = createSearchItem(
                    meta = meta,
                    title = title,
                    url = resolved,
                    poster = poster,
                    includeProvider = false,
                    query = null,
                    tvType = TvType.Anime
                ) ?: return@mapNotNull null
                item.response
            }
            if (items.isNotEmpty()) {
                homeLists += HomePageList(name, items, isHorizontalImages = true)
            }
        }
        return homeLists
    }

    private suspend fun searchAnimeSama(meta: ProviderMeta, query: String): List<SearchItem> {
        val searchUrl = "${meta.baseUrl.trimEnd('/')}/template-php/defaut/fetch.php"
        val doc = runCatching {
            app.post(
                searchUrl,
                referer = meta.baseUrl,
                data = mapOf("query" to query),
                headers = buildAjaxHeaders(searchUrl, meta.baseUrl)
            ).document
        }.getOrNull() ?: return emptyList()
        val dedupe = hashSetOf<String>()
        return doc.select("a[href]").mapNotNull { anchor ->
            val rawHref = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val resolved = resolveAgainst(meta.baseUrl, rawHref) ?: rawHref
            if (!dedupe.add(resolved)) return@mapNotNull null
            val poster = anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            var title = anchor.selectFirst("h1")?.text().orEmpty()
            if (title.isBlank()) title = anchor.selectFirst("h3")?.text().orEmpty()
            createSearchItem(
                meta = meta,
                title = title,
                url = resolved,
                poster = poster,
                includeProvider = true,
                query = query,
                tvType = TvType.Anime
            )
        }
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
        // Désactivé pour éviter les sections IMDB de secours
        return emptyList()
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
        if (meta?.slug.equals("frenchstream", ignoreCase = true)) {
            doc.select(".player-option, [data-player]").forEach { element ->
                element.attributes().forEach { attribute ->
                    val key = attribute.key.lowercase(Locale.ROOT)
                    if (key.startsWith("data-url") || key.startsWith("data-src") || key == "data-href" || key == "data-link") {
                        val raw = attribute.value.trim()
                        if (raw.isNotBlank()) {
                            val resolved = resolveAgainst(pageUrl, raw) ?: raw
                            if (resolved.startsWith("http", ignoreCase = true)) {
                                results += resolved
                            }
                        }
                    }
                }
            }
            val html = try {
                doc.outerHtml()
            } catch (_: Throwable) {
                null
            }
            if (!html.isNullOrBlank()) {
                val block = Regex("""playerUrls\s*=\s*\{([\s\S]*?)\};""").find(html)?.groups?.get(1)?.value
                if (!block.isNullOrBlank()) {
                    Regex("""https?://[^"'\\s<>]+""")
                        .findAll(block)
                        .mapNotNull { match -> match.value.trim().takeIf { url -> url.isNotBlank() } }
                        .forEach { raw ->
                            val resolved = resolveAgainst(pageUrl, raw) ?: raw
                            if (resolved.startsWith("http", ignoreCase = true)) {
                                results += resolved
                            }
                        }
                }
            }
        }
        val onclickRegex = Regex("""(?:loadVideo|showVideo)\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        doc.select("[onclick*=\"loadVideo\"], [onclick*=\"showVideo\"]").forEach { element ->
            val onclick = element.attr("onclick")
            onclickRegex.findAll(onclick).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    val resolved = resolveAgainst(pageUrl, raw) ?: raw
                    if (resolved.startsWith("http", ignoreCase = true)) {
                        results += resolved
                    }
                }
            }
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

    private fun buildFrenchStreamEmbeds(doc: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        doc.select(".player-option, [data-player]").forEach { element ->
            element.attributes().forEach { attribute ->
                val key = attribute.key.lowercase(Locale.ROOT)
                if (key.startsWith("data-url") || key.startsWith("data-src") || key == "data-href" || key == "data-link") {
                    val raw = attribute.value.trim()
                    if (raw.isNotBlank()) {
                        val resolved = resolveAgainst(pageUrl, raw) ?: raw
                        if (resolved.startsWith("http", ignoreCase = true)) {
                            links += resolved
                        }
                    }
                }
            }
        }
        val html = runCatching { doc.outerHtml() }.getOrNull()
        if (!html.isNullOrBlank()) {
            val block = Regex("""playerUrls\s*=\s*\{([\s\S]*?)\};""").find(html)?.groups?.get(1)?.value
            if (!block.isNullOrBlank()) {
                Regex("""https?://[^"'\\s<>]+""")
                    .findAll(block)
                    .mapNotNull { match -> match.value.trim().takeIf { url -> url.isNotBlank() } }
                    .forEach { raw ->
                        val resolved = resolveAgainst(pageUrl, raw) ?: raw
                        if (resolved.startsWith("http", ignoreCase = true)) {
                            links += resolved
                        }
                    }
            }
        }
        val priorityHosts = listOf("fsvid", "vidzy", "kakaflix", "voe", "dood", "uqload", "filemoon", "netu", "multiup")
        val prioritized = mutableListOf<String>()
        fun host(url: String): String = normalizeHost(url) ?: url.lowercase(Locale.ROOT)
        priorityHosts.forEach { target ->
            links.filter { host(it).contains(target) }.forEach { if (!prioritized.contains(it)) prioritized += it }
        }
        links.filterNot { prioritized.contains(it) }.forEach { prioritized += it }
        return prioritized
    }

    private fun preferredFsReferer(candidate: String, fallback: String): String {
        val host = normalizeHost(candidate) ?: return fallback
        return when {
            host.contains("vidzy.") -> "https://vidzy.org/"
            else -> fallback
        }
    }

    private suspend fun handleFrenchStreamEmbed(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = normalizeHost(embedUrl) ?: return false
        val needsSpecialHandling =
            host.contains("fsvid.") || host.contains("vidzy.") || host.contains("kakaflix.")
        if (!needsSpecialHandling) return false
        val baseReferer = preferredFsReferer(embedUrl, embedUrl)
        val response = runCatching { fetchHtml(embedUrl, referer = pageUrl) }.getOrNull() ?: return false
        val doc = response.document
        val html = response.text ?: runCatching { doc.outerHtml() }.getOrNull()
        val candidates = linkedSetOf<String>()
        candidates += collectAttributeValues(doc, "source@src", embedUrl)
        candidates += collectAttributeValues(doc, "video@src", embedUrl)
        candidates += collectAttributeValues(doc, "iframe@src", embedUrl)
        if (!html.isNullOrBlank()) {
            listOf(
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE),
                Regex("""['"]file['"]\s*[:=]\s*['"](https?://[^\s"'<>]+)['"]""", RegexOption.IGNORE_CASE)
            ).forEach { regex ->
                regex.findAll(html).forEach { match ->
                    val value = match.groupValues.lastOrNull()?.trim().takeIf { !it.isNullOrBlank() }
                        ?: match.value.trim()
                    if (value.isNotBlank()) {
                        candidates += value
                    }
                }
            }
        }
        var success = false
        val baseHeaders = buildMap {
            put("User-Agent", BROWSER_USER_AGENT)
            put("Referer", baseReferer)
            originFrom(baseReferer)?.let { put("Origin", it) }
        }
        candidates.take(12).forEach { candidate ->
            val candidateReferer = preferredFsReferer(candidate, baseReferer)
            val candidateHeaders = buildMap {
                putAll(baseHeaders)
                put("Referer", candidateReferer)
                originFrom(candidateReferer)?.let { put("Origin", it) }
            }
            when {
                candidate.contains(".m3u8", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = "FrenchStream",
                            name = "FrenchStream",
                            url = candidate,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = candidateReferer
                            this.headers = candidateHeaders
                            quality = Qualities.Unknown.value
                        }
                    )
                    success = true
                }
                candidate.endsWith(".mp4", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = "FrenchStream",
                            name = "FrenchStream",
                            url = candidate,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = candidateReferer
                            this.headers = candidateHeaders
                            quality = Qualities.Unknown.value
                        }
                    )
                    success = true
                }
                else -> {
                    try {
                        loadExtractor(candidate, candidateReferer, subtitleCallback, callback)
                        success = true
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return success
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

        fun parseCandidate(candidate: String): AjaxConfig? {
            val match = regex.find(candidate) ?: return null
            return parseAjaxConfigJson(match.groupValues[1], pageUrl)
        }

        parseCandidate(html)?.let { return it }

        doc.select("script").forEach { script ->
            val decoded = decodeDataUriScript(script)
            if (!decoded.isNullOrBlank()) {
                parseCandidate(decoded)?.let { return it }
            }
            val inline = script.data()
            if (!inline.isNullOrBlank()) {
                parseCandidate(inline)?.let { return it }
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

    private fun parseAjaxConfigJson(rawJson: String, pageUrl: String): AjaxConfig? {
        return runCatching {
            val json = JSONObject(rawJson)
            val rawUrl = json.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching null
            val resolvedUrl = resolveAgainst(pageUrl, rawUrl) ?: rawUrl
            val playerApi = json.optString("player_api").takeIf { it.isNotBlank() }
                ?.let { resolveAgainst(pageUrl, it) ?: it }
            val playMethod = sequenceOf(
                json.optString("play_method"),
                json.optString("play_ajaxmd"),
                json.optString("method")
            ).mapNotNull { it.takeIf { value -> value.isNotBlank() } }
                .firstOrNull()
            val classItem = when {
                json.has("classitem") -> {
                    json.optInt("classitem").takeIf { it > 0 }
                        ?: json.optString("classitem").toIntOrNull()
                }
                json.has("class_item") -> {
                    json.optInt("class_item").takeIf { it > 0 }
                        ?: json.optString("class_item").toIntOrNull()
                }
                else -> null
            }
            AjaxConfig(resolvedUrl, playMethod, playerApi, classItem)
        }.getOrNull()
    }

    private fun decodeDataUriScript(script: Element): String? {
        val src = script.attr("src").trim()
        if (!src.startsWith("data:text/javascript", ignoreCase = true)) return null
        val base64Marker = "base64,"
        val base64Part = src.substringAfter(base64Marker, missingDelimiterValue = "").takeIf { it.isNotBlank() }
            ?: return null
        val sanitized = base64Part.replace(Regex("\\s"), "")
        return runCatching {
            val bytes = Base64.getDecoder().decode(sanitized)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
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

    private fun isUnsBioLink(url: String): Boolean {
        return url.contains("uns.bio", ignoreCase = true)
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

    private fun parseSeasonNumberFromText(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        seasonLabelRegex.find(text)?.let { match ->
            return match.groupValues.getOrNull(2)?.toIntOrNull()
        }
        val digits = digitsRegex.find(text)?.value?.toIntOrNull()
        return digits
    }

    private fun parseEpisodeNumbersFromText(text: String?): Pair<Int?, Int?> {
        if (text.isNullOrBlank()) return null to null
        val normalized = text.replace("–", "-").replace("—", "-")
        seasonEpisodeComboRegex.find(normalized)?.let { match ->
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            return season to episode
        }
        dualNumberRegex.find(normalized)?.let { match ->
            val first = match.groupValues.getOrNull(1)?.toIntOrNull()
            val second = match.groupValues.getOrNull(2)?.toIntOrNull()
            return first to second
        }
        val numbers = digitsRegex.findAll(normalized)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        return when {
            numbers.size >= 2 -> numbers[0] to numbers[1]
            numbers.size == 1 -> null to numbers[0]
            else -> null to null
        }
    }

    private fun resolveImageSource(element: Element, pageUrl: String): String? {
        val attributes = listOf("data-src", "data-lazy-src", "data-original", "data-url", "srcset", "src")
        for (attr in attributes) {
            val raw = element.attr(attr).takeIf { it.isNotBlank() } ?: continue
            val candidate = if (attr.contains("srcset", ignoreCase = true)) {
                raw.split(",")
                    .mapNotNull { it.trim().split(" ").firstOrNull() }
                    .firstOrNull { it.isNotBlank() }
            } else raw
            val sanitized = candidate?.trim()?.takeIf { it.isNotBlank() } ?: continue
            if (sanitized.startsWith("data:", ignoreCase = true)) continue
            val absolute = when {
                sanitized.startsWith("http", ignoreCase = true) -> sanitized
                else -> resolveAgainst(pageUrl, sanitized)
            }
            if (!absolute.isNullOrBlank()) return absolute
        }
        return null
    }

    private fun extractEpisodePoster(element: Element, pageUrl: String): String? {
        val img = element.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[srcset], img[src]")
            ?: return null
        return resolveImageSource(img, pageUrl)
    }

    private fun extractPosterFromDoc(doc: Document, pageUrl: String): String? {
        val selectors = listOf(
            ".sheader .poster img",
            ".poster img",
            ".featured img",
            ".featured-image img",
            ".wp-post-image"
        )
        for (selector in selectors) {
            val element = doc.selectFirst(selector) ?: continue
            val resolved = resolveImageSource(element, pageUrl)
            if (!resolved.isNullOrBlank()) return resolved
        }
        val metaOg = doc.selectFirst("meta[property=og:image], meta[name=og:image]")?.attr("content")
        if (!metaOg.isNullOrBlank() && !metaOg.startsWith("data:", ignoreCase = true)) {
            return metaOg
        }
        return null
    }

    private fun collectEpisodesFromElements(
        elements: Iterable<Element>,
        defaultSeason: Int?,
        meta: ProviderMeta?,
        pageUrl: String,
        seen: MutableSet<String>
    ): List<Episode> {
        val results = mutableListOf<Episode>()
        var index = 0
        for (element in elements) {
            index++
            val anchor = element.selectFirst("a[href], .episodiotitle a") ?: continue
            val linkCandidates = listOf("href", "data-href", "data-url", "data-link")
            var target: String? = null
            for (attr in linkCandidates) {
                val raw = anchor.attr(attr).takeIf { it.isNotBlank() }
                    ?: element.attr(attr).takeIf { it.isNotBlank() }
                if (raw.isNullOrBlank()) continue
                target = when {
                    raw.startsWith("http", ignoreCase = true) -> raw
                    raw.startsWith("javascript", ignoreCase = true) -> null
                    else -> resolveAgainst(pageUrl, raw)
                }
                if (!target.isNullOrBlank()) break
            }
            if (target.isNullOrBlank()) continue
            if (!seen.add(target)) continue
            meta?.slug?.let { rememberSlugForUrl(target, it) }
            val rawTitle = anchor.text().ifBlank {
                element.selectFirst(".episodiotitle")?.text()
            }?.trim()
            val title = rawTitle?.takeIf { it.isNotBlank() } ?: "Episode $index"
            val numberText = element.selectFirst(".numerando")?.text()
                ?: element.attr("data-title")
                ?: anchor.attr("title")
            val (seasonFromText, episodeFromText) = parseEpisodeNumbersFromText(numberText)
            val seasonAttr = element.attr("data-season").toIntOrNull()
            val episodeAttr = element.attr("data-episode").toIntOrNull()
            val seasonNumber = seasonAttr ?: seasonFromText ?: defaultSeason ?: 1
            val episodeNumber = episodeAttr ?: episodeFromText ?: element.attr("data-num").toIntOrNull() ?: index
            val poster = extractEpisodePoster(element, pageUrl)
            val encoded = encodeLoadData(target, meta?.slug)
            results += newEpisode(encoded) {
                name = title
                season = seasonNumber
                episode = episodeNumber
                posterUrl = poster
            }
        }
        return results
    }

    private fun parseTvEpisodes(meta: ProviderMeta?, doc: Document, pageUrl: String): List<Episode> {
        val results = mutableListOf<Episode>()
        val seen = hashSetOf<String>()
        val seasonSelectors = listOf(
            "#seasons .se-c",
            ".seasons .se-c",
            ".season_list .se-c",
            ".items_seasons .se-c"
        )
        var foundContainers: List<Element> = emptyList()
        for (selector in seasonSelectors) {
            val found = doc.select(selector)
            if (found.isNotEmpty()) {
                foundContainers = found.toList()
                break
            }
        }
        if (foundContainers.isNotEmpty()) {
            var fallbackSeason = 1
            for (container in foundContainers) {
                val headerText = container.selectFirst(".se-q .se-t, .se-q .title, .se-q")?.text()
                val seasonNumber = container.attr("data-season").toIntOrNull()
                    ?: parseSeasonNumberFromText(headerText)
                    ?: fallbackSeason++
                val episodeElements = container.select("div.se-a ul li, ul.episodios li")
                if (episodeElements.isNotEmpty()) {
                    results += collectEpisodesFromElements(
                        episodeElements,
                        seasonNumber,
                        meta,
                        pageUrl,
                        seen
                    )
                }
            }
        }
        if (results.isEmpty()) {
            val fallbackSelectors = listOf(
                "#episodes li",
                ".episodes li",
                ".episodios li",
                ".episode-list li",
                ".items.episodes li"
            )
            for (selector in fallbackSelectors) {
                val fallbackItems = doc.select(selector)
                if (fallbackItems.isNotEmpty()) {
                    results += collectEpisodesFromElements(fallbackItems, null, meta, pageUrl, seen)
                    if (results.isNotEmpty()) break
                }
            }
        }
        return results
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
        val safeTitle = title.ifBlank { guessTitleFromUrl(url) ?: meta.displayName }
        val displayTitle = if (includeProvider) {
            "${meta.displayName} - $safeTitle"
        } else {
            safeTitle
        }
        val resolvedType = when {
            meta.slug.equals("AnimeSama", ignoreCase = true) -> TvType.Anime
            tvType == TvType.Anime -> TvType.Anime
            tvType == TvType.TvSeries -> TvType.TvSeries
            else -> TvType.Anime
        }
        val finalUrl = if (meta.slug.equals(NEBRYX_SLUG, ignoreCase = true) || meta.slug.equals(COFLIX_SLUG, ignoreCase = true)) {
            encodeLoadData(url, meta.slug)
        } else {
            url
        }
        val response = when (resolvedType) {
            TvType.TvSeries, TvType.Anime -> newTvSeriesSearchResponse(displayTitle, finalUrl, resolvedType)
            else -> newMovieSearchResponse(displayTitle, finalUrl, resolvedType)
        }
        if (!poster.isNullOrBlank()) {
            response.posterUrl = poster
        }
        val year = extractYearFrom(title)
        if (year != null && resolvedType == TvType.Movie) {
            (response as? MovieSearchResponse)?.year = year
        }
        val score = computeMatchScore(safeTitle, query)
        if (!query.isNullOrBlank() && score <= 0) return null
        return SearchItem(response, score)
    }

    private fun guessTitleFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val segment = runCatching { URI(url).path }.getOrNull()
            ?.trim('/')
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: return null
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrNull() ?: segment
        val cleaned = decoded.replace('-', ' ').replace('_', ' ').trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun parseCineplateformeAjax(doc: Document, baseUrl: String): List<AjaxRequest> {
        val regex = Regex("""getxfield\(\s*'(\d+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*\)""", RegexOption.IGNORE_CASE)
        val requests = linkedSetOf<AjaxRequest>()
        val onclickElements = doc.select("[onclick*=\"getxfield\"]")
        onclickElements.forEach { element ->
            val onclick = element.attr("onclick")
            regex.findAll(onclick).forEach { match ->
                val id = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@forEach
                val xfield = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@forEach
                val token = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() } ?: return@forEach
                val url = "${baseUrl.trimEnd('/')}/engine/ajax/getxfield.php?id=$id&xfield=$xfield&token=$token"
                requests += AjaxRequest(url, baseUrl)
            }
        }
        return requests.toList()
    }

    private suspend fun loadCineplateformeLinks(
        meta: ProviderMeta,
        pageUrl: String,
        doc: Document,
        pageHeaders: Headers?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = runCatching { URI(pageUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
            ?: meta.baseUrl
        val requests = parseCineplateformeAjax(doc, origin)
        if (requests.isEmpty()) return false
        var success = false
        val cookieHeader = ((pageHeaders?.values("set-cookie") ?: emptyList()) + (pageHeaders?.values("cookie") ?: emptyList()))
            .map { it.substringBefore(";") }
            .filter { it.isNotBlank() }
            .joinToString("; ")
            .ifBlank { null }
        for (req in requests) {
            val urlVariants = linkedSetOf(req.url)
            if (req.url.startsWith("https://www.", ignoreCase = true)) {
                urlVariants += req.url.replaceFirst("https://www.", "https://", ignoreCase = true)
                urlVariants += req.url.replaceFirst("https://www.", "http://", ignoreCase = true)
            } else if (req.url.startsWith("https://", ignoreCase = true)) {
                urlVariants += req.url.replaceFirst("https://", "http://", ignoreCase = true)
            }
            for (ajaxUrl in urlVariants) {
                val headers = buildHtmlHeaders(
                    ajaxUrl,
                    pageUrl,
                    buildMap {
                        put("X-Requested-With", "XMLHttpRequest")
                        put("Origin", origin)
                        if (cookieHeader != null) put("Cookie", cookieHeader)
                    }
                )
                val ajaxResponse = runCatching { app.get(ajaxUrl, referer = pageUrl, headers = headers) }.getOrNull()
                if (ajaxResponse == null) continue
                val body = ajaxResponse.text ?: ""
                fun protocolRelative(u: String?): String? =
                    u?.takeIf { it.startsWith("//") }?.let { "https:${it.removePrefix("//")}" }

                val direct = extractFirstMediaUrl(body) ?: protocolRelative(body)
                val ajaxDoc = ajaxResponse.document
                val iframe = ajaxDoc.selectFirst("iframe[src]")?.absUrl("src")?.ifBlank { null }
                val target = direct ?: iframe
                val links = linkedSetOf<String>()
                target?.let { links += it }
                ajaxDoc.select("a[href]").forEach { anchor ->
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    if (href.contains(".m3u8", true) || href.contains(".mp4", true) || href.contains(".mpd", true)) {
                        links += href
                    }
                }
                val prioritized = links
                    .sortedWith(
                        compareBy<String> { url ->
                            when {
                                url.contains("voe", true) -> 0
                                url.contains("filemoon", true) -> 1
                                url.contains("dood", true) || url.contains("dsvplay", true) -> 2
                                url.contains("netu", true) || url.contains("younetu", true) -> 3
                                url.contains("vidoza", true) -> 4
                                url.contains("uqload", true) -> 10 // évite de bloquer longtemps
                                else -> 5
                            }
                        }
                    )
                    .distinct()
                    .take(6)
                for (candidate in prioritized) {
                    if (candidate.contains("uqload", ignoreCase = true)) continue
                    val resolved = resolveAgainst(pageUrl, candidate) ?: candidate
                    val refererForLink = pageUrl
                    val ok = runCatching {
                        loadExtractor(resolved, refererForLink, subtitleCallback, callback)
                        true
                    }.getOrElse { false }
                    if (ok) {
                        success = true
                    }
                }
            }
            if (!success) {
                val fallbackOk = runCatching {
                    loadExtractor(req.url, pageUrl, subtitleCallback, callback)
                    true
                }.getOrElse { false }
                if (fallbackOk) success = true
            }
        }
        return success
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
            if (isCoflix(meta) && isNebryxUrl(href)) continue
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
                val firstTitle = responses.firstOrNull()?.name?.trim()
                val normalizedSection = sectionTitle.trim().lowercase(Locale.ROOT)
                val normalizedFirst = firstTitle?.lowercase(Locale.ROOT)
                val finalTitle = if (!normalizedFirst.isNullOrBlank() && normalizedSection == normalizedFirst) {
                    meta.displayName
                } else sectionTitle
                sections += HomePageList(finalTitle, responses)
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
            if (isCoflix(meta) && isNebryxUrl(resolved)) continue
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
            val poster = extractPoster(anchor, pageBase)
            val item = createSearchItem(
                meta = meta,
                title = title,
                url = resolved,
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

    override suspend fun search(query: String): List<SearchResponse> {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        if (metas.isEmpty()) return emptyList()
        val dedupe = hashSetOf<String>()
        val results = mutableListOf<SearchItem>()
        for (meta in metas) {
            try {
            if (isNebryx(meta)) {
                results += searchNebryx(meta, query)
                continue
            }
            if (isAnimeSama(meta)) {
                results += searchAnimeSama(meta, query)
                continue
            }
            if (isCoflix(meta)) {
                results += searchCoflix(meta, query)
                continue
            }
            val url = buildSearchUrl(meta.baseUrl, meta.rule, query) ?: continue
            val response = fetchHtml(url, referer = meta.baseUrl)
            val doc = response.document
            var items = if (meta.rule != null) {
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

    private suspend fun appendHomeListsForMeta(
        meta: ProviderMeta,
        page: Int,
        requestedSlug: String?,
        lists: MutableList<HomePageList>
    ): Boolean {
        val rule = meta.rule
        val cached = getCachedHomeLists(meta.slug)
        if (cached != null) {
            lists += cached
            return true
        }
        val beforeSize = lists.size
        fun finalizeWithCache(): Boolean {
            val added = if (lists.size > beforeSize) lists.subList(beforeSize, lists.size).toList() else emptyList()
            if (added.isNotEmpty()) {
                cacheHomeLists(meta.slug, added)
                return true
            }
            return false
        }
        return try {
            val dedupe = hashSetOf<String>()
            var handled = false
            if (isAnimeSama(meta) && (page == 1 || requestedSlug != null)) {
                val animeLists = runCatching { fetchAnimeSamaHome(meta) }.getOrElse { emptyList() }
                if (animeLists.isNotEmpty()) {
                    lists.addAll(animeLists)
                    handled = true
                }
            }
            if (isNebryx(meta) && (page == 1 || requestedSlug != null)) {
                val nebryxLists = runCatching { fetchNebryxHome(meta) }.getOrElse { emptyList() }
                if (nebryxLists.isNotEmpty()) {
                    lists.addAll(nebryxLists)
                    handled = true
                }
            }
            if (isFrenchTv(meta) && (page == 1 || requestedSlug != null)) {
                val frenchTvLists = runCatching { fetchFrenchTvHome(meta) }.getOrElse { emptyList() }
                if (frenchTvLists.isNotEmpty()) {
                    lists.addAll(frenchTvLists)
                    handled = true
                }
            }
            if (isCoflix(meta) && (page == 1 || requestedSlug != null)) {
                val coflixLists = runCatching { fetchCoflixHome(meta) }.getOrElse { emptyList() }
                if (coflixLists.isNotEmpty()) {
                    lists.addAll(coflixLists)
                    handled = true
                }
            }
            if (page == 1 && meta.slug.equals("1JOUR1FILM", ignoreCase = true)) {
                val mergedSections = LinkedHashMap<String, HomePageList>()
                fun addSections(sections: List<HomePageList>) {
                    sections.forEach { section ->
                        val sectionName = if (meta.displayName.isNotBlank()) {
                            "${meta.displayName} - ${section.name}"
                        } else section.name
                        val key = sectionName.trim().lowercase(Locale.ROOT)
                        if (key.isNotBlank() && !mergedSections.containsKey(key)) {
                            mergedSections[key] = HomePageList(sectionName, section.list)
                        }
                    }
                }
                val apiSections = runCatching { fetchOneJourHomeFromApi(meta) }.getOrElse { emptyList() }
                addSections(apiSections)
                val htmlSections = runCatching {
                    val response = fetchHtml(meta.baseUrl, referer = null)
                    extractOneJourHome(meta, response.document, dedupe)
                }.getOrElse { emptyList() }
                addSections(htmlSections)
                if (mergedSections.isNotEmpty()) {
                    lists.addAll(mergedSections.values)
                    return finalizeWithCache()
                }
            }
            if (handled) return finalizeWithCache()
            val effectiveRule = rule ?: return false
            val response = fetchHtml(meta.baseUrl, referer = null)
            val doc = response.document
            var items = extractWithRule(meta, doc, query = null, dedupe = dedupe, limit = 20, includeProvider = false)
            if (items.isEmpty() && isCoflix(meta)) {
                items = fallbackExtraction(meta, doc, query = null, dedupe = dedupe, limit = 25, includeProvider = false)
            }
            val responses = items.map { it.response }
            if (responses.isNotEmpty()) {
                lists += HomePageList(meta.displayName, responses)
                return finalizeWithCache()
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureRemoteConfigs()
        // On limite l’accueil à FrenchStream (films + séries) pour éviter les lenteurs
        val metas = gatherProviders()
        val targetMeta = when {
            forcedSlugLower != null -> metas.firstOrNull()
            else -> metas.firstOrNull { it.slug.equals("FrenchStream", ignoreCase = true) } ?: metas.firstOrNull()
        } ?: return newHomePageResponse(emptyList(), hasNext = false)
        val lists = mutableListOf<HomePageList>()
        appendHomeListsForMeta(targetMeta, page, targetMeta.slug, lists)
        return newHomePageResponse(lists, hasNext = false)
    }

    private data class LoadData(
        val url: String,
        val slug: String?,
        val imdbId: String?,
        val title: String?,
        val poster: String?,
        val year: Int?,
        val episode: Int?,
        val reader: Int?
    )

    private fun encodeLoadData(
        url: String,
        slug: String?,
        imdbId: String? = null,
        title: String? = null,
        poster: String? = null,
        year: Int? = null,
        episode: Int? = null,
        reader: Int? = null
    ): String {
        val obj = JSONObject()
        obj.put("url", url)
        slug?.takeIf { it.isNotBlank() }?.let { obj.put("slug", it) }
        imdbId?.takeIf { it.isNotBlank() }?.let { obj.put("imdbId", it) }
        title?.takeIf { it.isNotBlank() }?.let { obj.put("title", it) }
        poster?.takeIf { it.isNotBlank() }?.let { obj.put("poster", it) }
        year?.let { obj.put("year", it) }
        episode?.let { obj.put("episode", it) }
        reader?.let { obj.put("reader", it) }
        return obj.toString()
    }

    private fun decodeLoadData(data: String): LoadData {
        return runCatching {
            val obj = JSONObject(data)
            val targetUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: data
            val normalizedUrl = normalizeWebpanelUrl(targetUrl)
            val slug = obj.optString("slug").takeIf { it.isNotBlank() }
            val imdb = obj.optString("imdbId").takeIf { it.isNotBlank() }
            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val poster = obj.optString("poster").takeIf { it.isNotBlank() }
            val yearValue = obj.optInt("year")
            val year = if (obj.has("year") && yearValue > 0) yearValue else null
            val episode = if (obj.has("episode")) obj.optInt("episode") else null
            val reader = if (obj.has("reader")) obj.optInt("reader") else null
            LoadData(normalizedUrl, slug, imdb, title, poster, year, episode, reader)
        }.getOrElse {
            LoadData(normalizeWebpanelUrl(data), null, null, null, null, null, null, null)
        }
    }

    private fun normalizeWebpanelUrl(url: String): String {
        val webpanelPrefixes = listOf(
            "https://webpanel.invalid",
            "http://webpanel.invalid",
            "webpanel.invalid"
        )
        val matchedPrefix = webpanelPrefixes.firstOrNull { url.startsWith(it, ignoreCase = true) }
            ?: return url
        val trimmed = url.substring(matchedPrefix.length)
        val normalized = trimmed.removePrefix("/")
        return normalized.ifBlank { url }
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
            val loadData = decodeLoadData(url)
            val imdbFromData = loadData.imdbId
            val titleHint = loadData.title
            val posterHint = loadData.poster
            val yearHint = loadData.year
            val pageUrl = loadData.url
            val slugHint = loadData.slug
            if (pageUrl.isBlank()) return null
            val imdbId = when {
                url.startsWith("imdb://") -> url.removePrefix("imdb://")
                !imdbFromData.isNullOrBlank() -> imdbFromData
                else -> null
            }
            if (!imdbId.isNullOrBlank()) {
                val item = getCachedImdbItem(imdbId)
                    ?: if (!titleHint.isNullOrBlank()) {
                        ImdbItem(imdbId, titleHint, posterHint, yearHint)
                    } else {
                        fetchImdbDetails(imdbId)
                    }
                    ?: return null
                val dataPayload = encodeLoadData(
                    url = "imdb://$imdbId",
                    slug = slugHint,
                    imdbId = imdbId,
                    title = item.title,
                    poster = item.poster ?: posterHint,
                    year = item.year ?: yearHint
                )
                return newMovieLoadResponse(item.title, "imdb://$imdbId", TvType.Movie, dataUrl = dataPayload) { }.apply {
                    item.poster?.let { posterUrl = it }
                    item.year?.let { year = it }
                }
            }
            if (parseNebryxUrl(pageUrl) != null) {
                return loadNebryx(pageUrl)
            }
            if (isCoflixUrl(pageUrl) || slugHint.equals(COFLIX_SLUG, ignoreCase = true)) {
                return loadCoflixPage(pageUrl)
            }
            ensureRemoteConfigs()
            val metas = gatherProviders()
            val cachedSlug = slugHint ?: findSlugForUrl(pageUrl)
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
            val pageLocation = doc.location().ifBlank { pageUrl }
            val description = extractDescriptionFromDoc(doc)
                ?: fetchWordpressDescription(pageUrl, meta?.baseUrl ?: pageUrl)
            val title = titleHint
                ?: doc.selectFirst("title")?.text()?.trim()?.ifBlank { null }
                ?: meta?.displayName
                ?: name
            val poster = extractPosterFromDoc(doc, pageLocation)
            if (meta != null && isAnimeSama(meta)) {
                val panels = parseAnimeSamaPanels(doc, pageLocation)
                // Film panels doivent devenir une saison supplémentaire avec un seul épisode.
                val (filmPanels, seasonPanels) = panels.partition { it.first.contains("film", ignoreCase = true) }
                val orderedPanels = seasonPanels + filmPanels

                val seasonList = mutableListOf<com.lagradost.cloudstream3.SeasonData>()
                var seasonIndex = 1
                orderedPanels.forEach { (name, _) ->
                    seasonList += com.lagradost.cloudstream3.SeasonData(seasonIndex, name)
                    seasonIndex++
                }

                val subbedEpisodes = mutableListOf<Episode>()
                val dubbedEpisodes = mutableListOf<Episode>()
                seasonIndex = 1
                orderedPanels.forEach { (panelName, panelUrl) ->
                    val vostfrEpisodes = fetchAnimeSamaEpisodeLinks(panelUrl)
                    val vfUrl = panelUrl.replace("/vostfr/", "/vf/").replace("/vo/", "/vf/")
                    val vfEpisodes = fetchAnimeSamaEpisodeLinks(vfUrl)
                    val isFilm = panelName.contains("film", ignoreCase = true)
                    val maxCount = when {
                        isFilm -> 1
                        else -> maxOf(vostfrEpisodes.size, vfEpisodes.size)
                    }
                    for (i in 0 until maxCount) {
                        val baseName = when {
                            isFilm -> if (panelName.isNotBlank()) panelName else "Film"
                            else -> "$panelName - Episode ${i + 1}"
                        }
                        val subLinks = vostfrEpisodes.getOrNull(i) ?: emptyList()
                        if (subLinks.isNotEmpty()) {
                            val data = subLinks.joinToString(" ")
                            subbedEpisodes += newEpisode(data) {
                                name = baseName
                                episode = i + 1
                                season = seasonIndex
                                posterUrl = poster ?: posterHint
                            }
                        }
                        val dubLinks = vfEpisodes.getOrNull(i) ?: emptyList()
                        if (dubLinks.isNotEmpty()) {
                            val data = dubLinks.joinToString(" ")
                            dubbedEpisodes += newEpisode(data) {
                                name = baseName
                                episode = i + 1
                                season = seasonIndex
                                posterUrl = poster ?: posterHint
                            }
                        }
                    }
                    seasonIndex++
                }
                if (subbedEpisodes.isNotEmpty() || dubbedEpisodes.isNotEmpty()) {
                    return newAnimeLoadResponse(title, pageLocation, TvType.Anime) {
                        plot = description
                        posterUrl = poster ?: posterHint
                        addSeasonNames(seasonList)
                        if (subbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbedEpisodes)
                        if (dubbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbedEpisodes)
                    }
                }
            }
            val episodes = parseTvEpisodes(meta, doc, pageLocation)
            if (episodes.isNotEmpty()) {
                val seriesType = when (determineTvType(meta, pageUrl, null)) {
                    TvType.Anime -> TvType.Anime
                    else -> TvType.TvSeries
                }
                return newTvSeriesLoadResponse(title, pageLocation, seriesType, episodes) {
                    description?.let { plot = it }
                    (posterHint ?: poster)?.let { posterUrl = it }
                }
            }
            // Fallback: always emit an anime/series (never movie) with a single placeholder episode.
            val dataPayload = encodeLoadData(
                url = pageLocation,
                slug = meta?.slug ?: slugHint,
                title = title,
                poster = posterHint ?: poster
            )
            val placeholder = newEpisode(dataPayload) {
                name = "Episode 1"
                season = 1
                episode = 1
                posterUrl = poster ?: posterHint
            }
            newAnimeLoadResponse(title, pageLocation, TvType.Anime) {
                description?.let { plot = it }
                (posterHint ?: poster)?.let { posterUrl = it }
                addSeasonNames(listOf(com.lagradost.cloudstream3.SeasonData(1, "Saison 1")))
                addEpisodes(DubStatus.Subbed, listOf(placeholder))
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
        // Fast-path for AnimeSama concatenated links
        val parts = data.split(' ').filter { it.startsWith("http", ignoreCase = true) }
        if (parts.isNotEmpty()) {
            parts.forEach { link ->
                runCatching { loadExtractor(link, null, subtitleCallback, callback) }
            }
            return parts.isNotEmpty()
        }
        return try {
            HostersConfig.ensureLoaded()
            val hosterPatterns = buildHosterPatterns()
            val hosterAwareCallback = wrapCallbackWithHosters(hosterPatterns, callback)
            val loadData = decodeLoadData(data)
            val pageUrl = loadData.url
            val imdbId = loadData.imdbId
            if (loadData.slug.equals("AnimeSama", ignoreCase = true) || isAnimeSamaUrl(pageUrl)) {
                val episodeIndex = loadData.episode ?: 0
                val okAnime = runCatching {
                    loadAnimeSamaLinks(pageUrl, episodeIndex, subtitleCallback, hosterAwareCallback)
                }.getOrElse { false }
                if (okAnime) return true
            }
            val nebryxEntry = parseNebryxUrl(pageUrl)
            if (nebryxEntry != null || isNebryxSlug(loadData.slug)) {
                val ok = loadNebryxLinks(loadData, subtitleCallback, hosterAwareCallback)
                if (ok) return true
                val okEmbed = loadNebryxEmbed(pageUrl, subtitleCallback, hosterAwareCallback)
                if (okEmbed) return true
            }
            if (isCoflixUrl(pageUrl) || loadData.slug.equals(COFLIX_SLUG, ignoreCase = true)) {
                val coflixEntry = parseCoflixUrl(pageUrl)
                if (coflixEntry != null) {
                    val ok = loadCoflixLinks(coflixEntry, subtitleCallback, hosterAwareCallback)
                    if (ok) return true
                }
                val okSite = loadCoflixEmbed(pageUrl, subtitleCallback, hosterAwareCallback)
                if (okSite) return true
            }
            var imdb = imdbId
            if (imdb.isNullOrBlank() && nebryxEntry != null) {
                imdb = resolveImdbFromNebryx(nebryxEntry)
            }
            if (!imdb.isNullOrBlank()) {
                val embedUrl = "https://vidsrc.net/embed/movie?imdb=$imdb"
                val ok = runCatching {
                    loadExtractor(embedUrl, "https://vidsrc.net/", subtitleCallback, hosterAwareCallback)
                    true
                }.getOrElse { false }
                if (ok) return true
            }
            if (nebryxEntry != null) {
                val embedUrl = when (nebryxEntry.type.lowercase(Locale.ROOT)) {
                    "tv" -> "https://vidsrc.net/embed/tv?tmdb=${nebryxEntry.tmdbId}&season=${nebryxEntry.season ?: 1}&episode=${nebryxEntry.episode ?: 1}"
                    else -> "https://vidsrc.net/embed/movie?tmdb=${nebryxEntry.tmdbId}"
                }
                val ok = runCatching {
                    loadExtractor(embedUrl, "https://vidsrc.net/", subtitleCallback, hosterAwareCallback)
                    true
                }.getOrElse { false }
                if (ok) return true
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
            if (meta?.slug?.equals("cineplateforme", ignoreCase = true) == true) {
                val okCine = runCatching { loadCineplateformeLinks(meta, pageUrl, doc, response.headers, subtitleCallback, hosterAwareCallback) }.getOrElse { false }
                if (okCine) return true
            }
            if (meta?.slug.equals("frenchstream", ignoreCase = true)) {
                val fsEmbeds = buildFrenchStreamEmbeds(doc, pageUrl)
                fsEmbeds.take(15).forEach { link ->
                    val handled = runCatching {
                        handleFrenchStreamEmbed(link, pageUrl, subtitleCallback, hosterAwareCallback)
                    }.getOrDefault(false)
                    if (handled) return true
                    try {
                        loadExtractor(link, pageUrl, subtitleCallback, hosterAwareCallback)
                        return true
                    } catch (_: Throwable) {
                    }
                }
            }
            if (isFrenchTv(meta) || normalizeHost(pageUrl)?.contains("fstv.", ignoreCase = true) == true) {
                val ok = runCatching { loadFrenchTvPlayer(pageUrl, meta?.baseUrl, subtitleCallback, hosterAwareCallback) }.getOrElse { false }
                if (ok) return true
            }
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
                if (isUnsBioLink(link)) {
                    val handled = runCatching {
                        handleUnsBioEmbed(link, pageUrl, subtitleCallback, hosterAwareCallback)
                    }.getOrDefault(false)
                    if (handled) {
                        success = true
                    }
                } else if (meta?.slug.equals("frenchstream", ignoreCase = true)) {
                    val handled = runCatching {
                        handleFrenchStreamEmbed(link, pageUrl, subtitleCallback, hosterAwareCallback)
                    }.getOrDefault(false)
                    if (handled) {
                        success = true
                        return@forEach
                    }
                    try {
                        loadExtractor(link, pageUrl, subtitleCallback, hosterAwareCallback)
                        success = true
                    } catch (_: Throwable) {
                    }
                } else {
                    try {
                        loadExtractor(link, pageUrl, subtitleCallback, hosterAwareCallback)
                        success = true
                    } catch (_: Throwable) {
                    }
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

    private suspend fun handleUnsBioEmbed(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = runCatching { URI(embedUrl) }.getOrNull() ?: return false
        val baseUrl = "${uri.scheme}://${uri.host}"
        val videoId = when {
            !uri.fragment.isNullOrBlank() -> uri.fragment
            uri.query != null -> uri.query.split("&").mapNotNull {
                val parts = it.split("=")
                if (parts.size == 2 && parts[0].equals("id", ignoreCase = true)) parts[1] else null
            }.firstOrNull()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return false

        val refererHost = runCatching { URI(pageUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: uri.host.removePrefix("www.")

        val commonHeaders = LinkedHashMap<String, String>(8)
        commonHeaders["User-Agent"] = BROWSER_USER_AGENT
        commonHeaders["Accept"] = ACCEPT_JSON
        commonHeaders["Accept-Language"] = ACCEPT_LANGUAGE
        commonHeaders["Origin"] = baseUrl
        commonHeaders["Referer"] = embedUrl
        commonHeaders["Connection"] = "keep-alive"
        commonHeaders["Cache-Control"] = "no-cache"
        commonHeaders["Sec-Fetch-Mode"] = "cors"
        commonHeaders["Sec-Fetch-Site"] = "same-origin"
        commonHeaders["Sec-Fetch-Dest"] = "empty"

        fun absolute(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return resolveAgainst(baseUrl, path) ?: runCatching { URI(baseUrl + path).toString() }.getOrNull()
        }

        suspend fun fetchPayload(url: String): JSONObject? {
            val response = runCatching {
                app.get(url, referer = embedUrl, headers = commonHeaders)
            }.getOrNull() ?: return null
            return decryptUnsPayload(response.text)
        }

        val infoUrl = "$baseUrl/api/v1/info?id=$videoId"
        val infoJson = fetchPayload(infoUrl)
        val playerId = infoJson?.optString("playerId")?.takeIf { it.isNotBlank() } ?: videoId

        val screenWidth = "1920"
        val screenHeight = "1080"
        val videoUrl = "$baseUrl/api/v1/video?id=$playerId&w=$screenWidth&h=$screenHeight&r=$refererHost"
        val videoJson = fetchPayload(videoUrl)

        val downloadUrl = "$baseUrl/api/v1/download?id=$playerId&w=$screenWidth&h=$screenHeight&r=$refererHost"
        val downloadJson = fetchPayload(downloadUrl)

        var success = false
        val seenSubtitles = hashSetOf<String>()

        fun emitSubtitles(container: JSONObject?) {
            container ?: return
            val iterator = container.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = container.optString(key).takeIf { it.isNotBlank() } ?: continue
                val normalized = key.lowercase(Locale.ROOT)
                if (seenSubtitles.add(normalized)) {
                    val finalUrl = absolute(value) ?: continue
                    subtitleCallback(SubtitleFile(normalized, finalUrl))
                }
            }
        }

        emitSubtitles(videoJson?.optJSONObject("subtitle"))
        emitSubtitles(downloadJson?.optJSONObject("subtitle"))

        val hlsUrl = videoJson?.optString("source")?.takeIf { it.isNotBlank() }
        if (hlsUrl != null) {
            val finalHlsUrl = absolute(hlsUrl) ?: hlsUrl
            callback(
                newExtractorLink(
                    source = "UnsBio",
                    name = "UnsBio",
                    url = finalHlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = embedUrl
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to BROWSER_USER_AGENT,
                        "Referer" to embedUrl
                    )
                }
            )
            success = true
        }

        val mp4Url = downloadJson?.optString("mp4")?.takeIf { it.isNotBlank() }
        if (mp4Url != null) {
            val finalMp4Url = absolute(mp4Url) ?: mp4Url
            callback(
                newExtractorLink(
                    source = "UnsBio",
                    name = "UnsBio",
                    url = finalMp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = embedUrl
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to BROWSER_USER_AGENT,
                        "Referer" to embedUrl
                    )
                }
            )
            success = true
        }

        return success
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
        val movies = fetchWpCollection(meta, apiBase, "movies", 50, TvType.Movie)
        val shows = fetchWpCollection(meta, apiBase, "tvshows", 50, TvType.TvSeries)
        val seasons = fetchWpCollection(meta, apiBase, "seasons", 50, TvType.TvSeries)
        val episodes = fetchWpCollection(meta, apiBase, "episodes", 50, TvType.TvSeries)

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
