package com.gramflix.extensions.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.RulesConfig
import org.jsoup.Jsoup

class ConfigDrivenProvider : MainAPI() {
    override var name = "GramFlix Dynamic"
    override var mainUrl = "https://cs.tafili.fr"
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
        val json = java.util.concurrent.ConcurrentLinkedQueue<SearchResponse>()
        val providers = RemoteConfig.providersObject()
        providers?.keys()?.forEach { key ->
            val slug = key as String
            val item = providers.optJSONObject(slug) ?: return@forEach
            val baseUrl = item.optString("baseUrl", null) ?: return@forEach
            val rule = parseRule(slug) ?: return@forEach
            try {
                val url = okhttp3.HttpUrl.parse(baseUrl)?.newBuilder()?.apply {
                    addEncodedPathSegments(rule.searchPath.trimStart('/'))
                    addQueryParameter(rule.searchParam, query)
                }?.build()?.toString() ?: return@forEach
                val res = app.get(url, referer = baseUrl)
                val doc = res.document
                doc.select(rule.itemSel).forEach { card ->
                    val title = selectAttrOrText(card, rule.titleSel) ?: return@forEach
                    val href = selectAttrOrText(card, rule.urlSel) ?: return@forEach
                    val absUrl = org.jsoup.helper.StringUtil.resolve(baseUrl, href)
                    json.add(MovieSearchResponse(title, absUrl, this.name, TvType.Movie))
                }
            } catch (_: Throwable) { /* ignore site errors */ }
        }
        return json.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = url).document
            val title = doc.selectFirst("title")?.text()?.trim()?.ifBlank { null } ?: name
            MovieLoadResponse(title, url, this.name, url)
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
                try { AppUtils.loadExtractor(link, data, subtitleCallback, callback) } catch (_: Throwable) {}
            }
            candidates.isNotEmpty()
        } catch (_: Throwable) { false }
    }
}
