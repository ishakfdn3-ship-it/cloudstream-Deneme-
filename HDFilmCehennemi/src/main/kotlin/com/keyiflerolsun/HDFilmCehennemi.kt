package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/category/film-izle-2/"            to "Yeni Filmler",
        "${mainUrl}/category/tavsiye-filmler-izle3/"  to "Tavsiye Filmler",
        "${mainUrl}/category/1080p-hd-film-izle-2/"   to "1080p Filmler",
        "${mainUrl}/yabancidiziizle-6/"               to "Yabancı Diziler",
        "${mainUrl}/yil/2025-filmleri-izle-3/"        to "2025 Filmleri",
        "${mainUrl}/yil/2024-2/"                      to "2024 Filmleri"
    )

    // ---------- jspecify-safe yardımcılar ----------

    private fun safeText(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.text() } catch (e: Throwable) { null }
    }

    private fun safeAttr(el: Element?, key: String): String? {
        if (el == null) return null
        return try { "" + el.attr(key) } catch (e: Throwable) { null }
    }

    private fun safeData(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.data() } catch (e: Throwable) { null }
    }

    private fun safeOwnText(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.ownText() } catch (e: Throwable) { null }
    }

    private fun unpackScript(packed: String): String {
        return try {
            val clazz = Class.forName("com.lagradost.common.JsUnpacker")
            val ctor = clazz.getConstructor(String::class.java)
            val instance = ctor.newInstance(packed)
            val method = clazz.getMethod("unpack")
            (method.invoke(instance) as? String) ?: packed
        } catch (e: Throwable) {
            packed
        }
    }

    private fun findGroupInt(input: String, pattern: String, group: Int = 1): Int? {
        return try {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(input) ?: return null
            val values = match.groupValues
            if (values.size <= group) return null
            values[group].toIntOrNull()
        } catch (e: Throwable) { null }
    }

    // ---------- ana sayfa ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl  = request.data.trimEnd('/')
        val url      = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val document = app.get(url).document

        val home = document.select("a.poster, div.poster-card, article.poster").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val hrefRaw: String? = safeAttr(anchor, "href")
        val href: String = fixUrlNull(hrefRaw) ?: return null

        val titleA: String? = safeText(this.selectFirst("h2, h3, .title, .poster-title, strong"))
        val titleB: String? = safeAttr(anchor, "title")?.ifEmpty { null }
        val titleC: String? = safeAttr(this.selectFirst("img"), "alt")?.ifEmpty { null }
        val title: String = titleA ?: titleB ?: titleC ?: return null

        val cleanTitle = title.replace("izle", "", ignoreCase = true).trim()

        val img = this.selectFirst("img")
        val posterSrc: String? = safeAttr(img, "data-src")?.ifEmpty { safeAttr(img, "src") }
        val posterUrl: String? = fixUrlNull(posterSrc)

        val selfText: String? = safeText(this)
        val isSeries: Boolean = href.contains("dizi", ignoreCase = true) ||
                                selfText?.contains("Sezon", ignoreCase = true) == true

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response: String = app.get(
            "${mainUrl}/search/?q=${query}",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "Referer"          to "$mainUrl/"
            )
        ).text

        val results = mutableListOf<SearchResponse>()

        val jsonSearch: SearchJson? = tryParseJson<SearchJson>(response)
        val jsonResults = jsonSearch?.results
        if (jsonResults != null && jsonResults.isNotEmpty()) {
            for (htmlItem in jsonResults) {
                val doc = Jsoup.parse(htmlItem)
                val first = doc.selectFirst("a, div.search-item")
                val result = first?.toSearchResult()
                if (result != null) results.add(result)
            }
            if (results.isNotEmpty()) return results
        }

        val doc = Jsoup.parse(response)
        return doc.select("a.poster, a.search-item, div.result-group a").mapNotNull { it.toSearchResult() }
    }

    // ---------- detay ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val ldScriptEl: Element? = document.selectFirst("script[type=application/ld+json]")
        val ldJsonScript: String? = safeData(ldScriptEl)
        val ldData: LdJson? = tryParseJson<LdJson>(ldJsonScript)

        val rawTitleA: String? = ldData?.name
        val rawTitleB: String? = safeOwnText(document.selectFirst("h1.section-title"))
            ?.replace("izle", "", ignoreCase = true)?.trim()
        val rawTitle: String = rawTitleA ?: rawTitleB ?: return null

        val subTitle: String = safeText(document.selectFirst("h1.section-title small")) ?: ""
        val yearFromSub: Int? = findGroupInt(subTitle, """\((\d{4})\)""")
        val yearFromLd: Int? = ldData?.datePublished?.let {
            if (it.length >= 4) it.substring(0, 4).toIntOrNull() else null
        }
        val year: Int? = yearFromSub ?: yearFromLd

        val posterEl = document.selectFirst("div.post-info-poster img, img.poster")
        val posterSrc: String? = ldData?.image
            ?: safeAttr(posterEl, "data-src")?.ifEmpty { safeAttr(posterEl, "src") }
        val poster: String? = fixUrlNull(posterSrc)

        val description: String? = ldData?.description
            ?: safeText(document.selectFirst("article.post-info-content p, div.post-info-content"))?.trim()

        val tags: List<String> = ldData?.genre
            ?: document.select("div.post-info-genres a").map { safeText(it) ?: "" }.filter { it.isNotEmpty() }
        val actors: List<String> = ldData?.actor?.mapNotNull { it.name } ?: emptyList()
        val imdbId: String? = ldData?.sameAs?.firstOrNull { it.contains("imdb.com") }?.substringAfterLast("/")

        val episodeElements = document.select("div.seasons-tab-content a, div.episodes-list a, a.episode-item")
        val isSeries: Boolean = episodeElements.isNotEmpty() || ldData?.type.equals("TVSeries", ignoreCase = true)

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHrefRaw: String? = safeAttr(ep, "href")
                val epHref: String = fixUrlNull(epHrefRaw) ?: return@mapNotNull null
                val epName: String = safeText(ep) ?: ""

                val seasonParent = ep.parents().firstOrNull { it.hasAttr("data-season") }
                val seasonFromName: Int? = findGroupInt(epName, """(\d+)\.\s*Sezon""")
                val seasonFromAttr: Int? = safeAttr(seasonParent, "data-season")?.toIntOrNull()
                val seasonNum: Int = seasonFromName ?: seasonFromAttr ?: 1

                val episodeNum: Int? = findGroupInt(epName, """(\d+)\.\s*Bölüm""")

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = seasonNum
                    this.episode = episodeNum
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot      = description
                    this.year      = year
                    this.tags      = tags
                    addActors(actors)
                    addImdbId(imdbId)
                }
            }
        }

        return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addImdbId(imdbId)
        }
    }

    // ---------- video linkleri ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val processedUrls = mutableSetOf<String>()

        val mainIframe = document.selectFirst("div.video-container iframe")
        if (mainIframe != null) {
            val iframeSrcRaw: String? = safeAttr(mainIframe, "data-src")?.ifEmpty { safeAttr(mainIframe, "src") }
            val iframeSrc: String? = fixUrlNull(iframeSrcRaw)
            if (iframeSrc != null && processedUrls.add(iframeSrc)) {
                extractVideo(iframeSrc, data, "Varsayılan", subtitleCallback, callback)
            }
        }

        for (button in document.select("button.alternative-link")) {
            val videoId: String = safeAttr(button, "data-video") ?: ""
            if (videoId.isBlank()) continue

            val langParent = button.parents().firstOrNull { it.hasClass("alternative-links") }
            val langCode: String = safeAttr(langParent, "data-lang")?.uppercase() ?: "TR"
            val altName: String = safeText(button)?.trim() ?: ""
            val label = "$langCode - $altName"

            val responseText: String? = try {
                app.get(
                    "${mainUrl}/video/$videoId/",
                    headers = mapOf(
                        "X-Requested-With" to "fetch",
                        "Referer"          to data
                    )
                ).text
            } catch (e: Throwable) { null }

            if (responseText == null) continue

            val cleanHtml = responseText.replace("\\\"", "\"").replace("\\/", "/")
            val iframeEl = Jsoup.parse(cleanHtml).selectFirst("iframe")
            val iframeFromTag: String? = safeAttr(iframeEl, "data-src")?.ifEmpty { safeAttr(iframeEl, "src") }
            val iframeFromRegex: String? = Regex("""src=["'](https?://[^"']+)["']""")
                .find(cleanHtml)?.groupValues?.get(1)
            val iframeSrc: String? = fixUrlNull(iframeFromTag ?: iframeFromRegex)

            if (iframeSrc != null && processedUrls.add(iframeSrc)) {
                extractVideo(iframeSrc, data, label, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun extractVideo(
        iframeUrl: String,
        referer: String,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (loadExtractor(iframeUrl, referer, subtitleCallback, callback)) return
        } catch (e: Throwable) {}

        try {
            val embedResponse = app.get(iframeUrl, referer = referer)
            val embedDoc      = embedResponse.document
            val embedText     = embedResponse.text
            val embedHost: String = Regex("""https?://[^/]+""").find(iframeUrl)?.value ?: mainUrl

            // Altyazılar
            Regex("""["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["']""")
                .findAll(embedText).forEach { match ->
                    val subUrlRaw: String = match.groupValues[1]
                    val subUrl = if (subUrlRaw.startsWith("http")) subUrlRaw else "$embedHost$subUrlRaw"
                    val subLang: String = match.groupValues[2]
                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                }

            // Packed script varsa unpack et
            var unpacked: String = embedText
            for (script in embedDoc.select("script")) {
                val scriptData: String? = safeData(script)
                if (scriptData != null && scriptData.contains("eval(function(p,a,c,k,e,d)")) {
                    unpacked = unpackScript(scriptData)
                    break
                }
            }

            // 1) dc_hello base64
            val dcMatch = Regex("""dc_hello\(\s*\[([^\]]+)\]\s*\)|dc_hello\(\s*["']([^"']+)["']\s*\)""").find(unpacked)
            if (dcMatch != null) {
                val rawParts: String = dcMatch.groupValues[1].ifEmpty { dcMatch.groupValues[2] }
                val joined: String = rawParts.replace("\"", "").replace("'", "")
                    .replace(",", "").replace(" ", "")

                var decoded: String = ""
                try {
                    decoded = String(Base64.decode(joined, Base64.URL_SAFE))
                } catch (e: Throwable) {
                    try { decoded = String(Base64.decode(joined, Base64.DEFAULT)) } catch (e2: Throwable) { decoded = "" }
                }

                if (decoded.isNotBlank()) {
                    val m3u8Match = Regex("""https?://[^\s"']+\.(?:m3u8|txt)[^\s"']*""").find(decoded)
                    val m3u8Url: String = m3u8Match?.value ?: decoded
                    if (m3u8Url.startsWith("http")) {
                        M3u8Helper.generateM3u8(
                            source    = "${this.name} ($sourceLabel)",
                            streamUrl = m3u8Url,
                            referer   = iframeUrl
                        ).forEach(callback)
                        return
                    }
                }
            }

            // 2) file/src m3u8
            val directMatch = Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)
            if (directMatch != null) {
                val m3u8Url: String = directMatch.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    source    = "${this.name} ($sourceLabel)",
                    streamUrl = m3u8Url,
                    referer   = iframeUrl
                ).forEach(callback)
                return
            }

            // 3) eski sources:
            for (script in embedDoc.select("script")) {
                val raw = safeData(script) ?: continue
                if (!raw.contains("sources:")) continue
                val unpackedScript = unpackScript(raw)
                val srcMatch = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpackedScript)
                if (srcMatch != null) {
                    M3u8Helper.generateM3u8(
                        source    = "${this.name} ($sourceLabel)",
                        streamUrl = srcMatch.groupValues[1].replace("\\/", "/"),
                        referer   = iframeUrl
                    ).forEach(callback)
                }
                break
            }
        } catch (e: Throwable) {
            // sessizce devam
        }
    }

    data class SearchJson(
        @JsonProperty("results") val results: List<String>? = null
    )

    data class LdJson(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("datePublished") val datePublished: String? = null,
        @JsonProperty("genre") val genre: List<String>? = null,
        @JsonProperty("sameAs") val sameAs: List<String>? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("actor") val actor: List<LdActor>? = null
    )

    data class LdActor(
        @JsonProperty("name") val name: String? = null
    )
}
