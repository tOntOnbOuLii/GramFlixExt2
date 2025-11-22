package com.gramflix.extensions.providers

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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
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

class ConfigDrivenProvider : MainAPI() {
    override var name = "GramFlix Dynamic"
    override var mainUrl = "https://webpanel.invalid"
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() {
            ensureRemoteConfigs()
            HomeConfig.ensureLoaded()
            val metas = gatherProviders()
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
        val rule: Rule?
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
        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_DEFAULT_LANGUAGE = "fr-FR"
        private const val DEFAULT_NEBRYX_BASE = "https://nebryx.fr"
        private const val FREMBED_SLUG = "frembed"
        private const val DEFAULT_FREMBED_BASE = "https://frembed.my"
        private const val HOME_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val HOME_CACHE_MAX_ENTRIES = 32
        private const val TMDB_API_KEY = "660883a8a688af69b7e1d834f864e006"
        private const val TMDB_CACHE_TTL_MS = 10 * 60 * 1000L
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

    private suspend fun searchCoflix(meta: ProviderMeta, query: String): List<SearchItem> {
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

    private suspend fun fetchCoflixHome(meta: ProviderMeta): List<HomePageList> {
        val sections = mutableListOf<HomePageList>()
        val endpoints = listOf(
            Triple("Films populaires", "/movie/popular", TvType.Movie),
            Triple("Top films", "/movie/top_rated", TvType.Movie),
            Triple("Sorties à venir", "/movie/upcoming", TvType.Movie),
            Triple("Séries populaires", "/tv/popular", TvType.TvSeries),
            Triple("Top séries", "/tv/top_rated", TvType.TvSeries)
        )
        for ((label, path, tvType) in endpoints) {
            val json = tmdbGet(path) ?: continue
            val typeSlug = if (path.contains("/tv/")) "tv" else "movie"
            val entries = buildNebryxResponses(
                meta = meta,
                array = json.optJSONArray("results"),
                limit = 20,
                includeProvider = true,
                type = typeSlug,
                tvType = tvType
            )
            if (entries.isNotEmpty()) {
                sections += HomePageList("${meta.displayName} - $label", entries)
            }
        }
        return sections
    }

    private suspend fun buildNebryxEpisodes(tmdbId: Int, seasons: JSONArray?): List<Episode> {
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
                val epUrl = buildNebryxUrl("tv", tmdbId, seasonNumber, episodeNumber)
                val encoded = encodeLoadData(epUrl, NEBRYX_SLUG)
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

    private suspend fun loadNebryxSeries(entry: NebryxEntry): LoadResponse? {
        val tmdbId = entry.tmdbId
        val json = tmdbGet("/tv/$tmdbId") ?: return null
        val title = json.optString("name").takeIf { it.isNotBlank() }
            ?: json.optString("original_name").takeIf { it.isNotBlank() }
            ?: "Nebryx"
        val overview = json.optString("overview").takeIf { it.isNotBlank() }
        val poster = buildNebryxPoster(json.optString("poster_path"))
        val year = tmdbReleaseYear(json.optString("first_air_date"))
        val episodes = buildNebryxEpisodes(tmdbId, json.optJSONArray("seasons"))
        if (episodes.isEmpty()) return null
        val canonicalUrl = buildNebryxUrl("tv", tmdbId)
        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
            overview?.let { plot = it }
            poster?.let { posterUrl = it }
            year?.let { this.year = it }
        }
    }

    private suspend fun loadNebryxLinks(
        data: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entry = parseNebryxUrl(data.url) ?: return false
        val referer = data.url.takeIf { it.isNotBlank() } ?: nebryxBaseUrl()
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
        return when (entry.type) {
            "movie" -> {
                val embedUrl = "$embedBase/api/film.php?id=${entry.tmdbId}"
                val playerUrl = resolveFrembedPlayerUrl(embedUrl, referer)
                runCatching {
                    loadExtractor(playerUrl, embedUrl, countingSubtitle, countingCallback)
                    emitted
                }.getOrElse { false }
            }
            "tv" -> {
                val season = entry.season ?: return false
                val episode = entry.episode ?: return false
                val embedUrl = "$embedBase/api/serie.php?id=${entry.tmdbId}&sa=$season&epi=$episode"
                val playerUrl = resolveFrembedPlayerUrl(embedUrl, referer)
                runCatching {
                    loadExtractor(playerUrl, embedUrl, countingSubtitle, countingCallback)
                    emitted
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

    private fun isNebryx(meta: ProviderMeta?): Boolean =
        meta?.slug?.equals(NEBRYX_SLUG, ignoreCase = true) == true

    private fun isNebryxSlug(slug: String?): Boolean =
        slug?.equals(NEBRYX_SLUG, ignoreCase = true) == true

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
        meta?.slug?.equals("coflix", ignoreCase = true) == true

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

    private fun parseNebryxUrl(url: String): NebryxEntry? {
        return parseNebryxSchemeUrl(url) ?: parseNebryxWatchUrl(url)
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
        val finalUrl = if (meta.slug.equals(NEBRYX_SLUG, ignoreCase = true)) {
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
                val url = buildSearchUrl(meta.baseUrl, meta.rule, query) ?: continue
                val response = fetchHtml(url, referer = meta.baseUrl)
                val doc = response.document
                var items = if (meta.rule != null) {
                    extractWithRule(meta, doc, query, dedupe, limit = 25, includeProvider = true)
                } else {
                    fallbackExtraction(meta, doc, query, dedupe, limit = 15, includeProvider = true)
                }
                // Pas de fallback TMDB pour Coflix afin d'éviter les URLs Nebryx : on reste sur le scraping du site.
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
            if (isNebryx(meta) && (page == 1 || requestedSlug != null)) {
                val nebryxLists = runCatching { fetchNebryxHome(meta) }.getOrElse { emptyList() }
                if (nebryxLists.isNotEmpty()) {
                    lists.addAll(nebryxLists)
                    handled = true
                }
            }
            if (page == 1 && meta.slug.equals("1JOUR1FILM", ignoreCase = true)) {
                val apiSections = runCatching { fetchOneJourHomeFromApi(meta) }.getOrElse { emptyList() }
                if (apiSections.isNotEmpty()) {
                    lists.addAll(apiSections)
                    handled = true
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
            if (meta.slug.equals("1JOUR1FILM", ignoreCase = true)) {
                val fallbackSections = extractOneJourHome(meta, doc, dedupe)
                if (fallbackSections.isNotEmpty()) {
                    lists.addAll(fallbackSections)
                    return finalizeWithCache()
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureRemoteConfigs()
        val metas = gatherProviders()
        val requestedSlug = request.data?.takeIf { it.isNotBlank() }
        val filteredMetas = when {
            requestedSlug == null -> metas
            requestedSlug.equals(FALLBACK_HOME_KEY, ignoreCase = true) -> emptyList()
            else -> metas.filter { it.slug.equals(requestedSlug, ignoreCase = true) }
        }.ifEmpty {
            if (requestedSlug == null || requestedSlug.equals(FALLBACK_HOME_KEY, ignoreCase = true)) emptyList() else metas
        }

        val pageSize = 5
        val lists = mutableListOf<HomePageList>()
        var hasNextProviders = false
        if (filteredMetas.isNotEmpty()) {
            if (requestedSlug == null) {
                val startIndex = max(0, (page - 1) * pageSize)
                if (startIndex < filteredMetas.size) {
                    var index = startIndex
                    var successes = 0
                    while (index < filteredMetas.size && successes < pageSize) {
                        val meta = filteredMetas[index]
                        index++
                        val added = appendHomeListsForMeta(meta, page, requestedSlug, lists)
                        if (added) {
                            successes++
                        }
                    }
                    hasNextProviders = index < filteredMetas.size
                }
            } else {
                filteredMetas.forEach { meta ->
                    appendHomeListsForMeta(meta, page, requestedSlug, lists)
                }
            }
        }

        val fallbackLists = if (page == 1) {
            HomeConfig.ensureLoaded()
            loadHomeFallback()
        } else emptyList()

        if (requestedSlug?.equals(FALLBACK_HOME_KEY, ignoreCase = true) == true) {
            if (fallbackLists.isNotEmpty()) {
                return newHomePageResponse(fallbackLists, hasNext = false)
            }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (lists.isEmpty()) {
            if (fallbackLists.isNotEmpty()) {
                return newHomePageResponse(fallbackLists, hasNext = false)
            }
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (fallbackLists.isNotEmpty() && requestedSlug == null) {
            lists.addAll(fallbackLists)
        }

        val hasNext = requestedSlug == null && hasNextProviders

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
            val normalizedUrl = normalizeWebpanelUrl(targetUrl)
            val slug = obj.optString("slug").takeIf { it.isNotBlank() }
            val imdb = obj.optString("imdbId").takeIf { it.isNotBlank() }
            val title = obj.optString("title").takeIf { it.isNotBlank() }
            val poster = obj.optString("poster").takeIf { it.isNotBlank() }
            val yearValue = obj.optInt("year")
            val year = if (obj.has("year") && yearValue > 0) yearValue else null
            LoadData(normalizedUrl, slug, imdb, title, poster, year)
        }.getOrElse {
            LoadData(normalizeWebpanelUrl(data), null, null, null, null, null)
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
            val dataPayload = encodeLoadData(
                url = pageLocation,
                slug = meta?.slug ?: slugHint,
                imdbId = imdbFromData,
                title = title,
                poster = posterHint ?: poster,
                year = yearHint
            )
            newMovieLoadResponse(title, pageLocation, TvType.Movie, dataUrl = dataPayload) {
                description?.let { plot = it }
                (posterHint ?: poster)?.let { posterUrl = it }
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
            HostersConfig.ensureLoaded()
            val hosterPatterns = buildHosterPatterns()
            val hosterAwareCallback = wrapCallbackWithHosters(hosterPatterns, callback)
            val loadData = decodeLoadData(data)
            val pageUrl = loadData.url
            val imdbId = loadData.imdbId
            val nebryxEntry = parseNebryxUrl(pageUrl)
            if (nebryxEntry != null || isNebryxSlug(loadData.slug)) {
                val ok = loadNebryxLinks(loadData, subtitleCallback, hosterAwareCallback)
                if (ok) return true
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
                if (isUnsBioLink(link)) {
                    val handled = runCatching {
                        handleUnsBioEmbed(link, pageUrl, subtitleCallback, hosterAwareCallback)
                    }.getOrDefault(false)
                    if (handled) {
                        success = true
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
