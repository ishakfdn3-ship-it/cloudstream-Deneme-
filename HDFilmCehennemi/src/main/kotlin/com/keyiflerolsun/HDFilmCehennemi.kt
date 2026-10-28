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
        val href   = fixUrlNull(anchor.attr("href")) ?: return null

        val title = this.selectFirst("h2, h3, .title, .poster-title, strong")?.text()?.trim()
            ?: anchor.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null

        val cleanTitle = title.replace("izle", "", ignoreCase = true).trim()

        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

        val isSeries = href.contains("dizi", ignoreCase = true) ||
                       this.text().contains("Sezon", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search/?q=${query}",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "Referer"          to "$mainUrl/"
            )
        ).text

        val results = mutableListOf<SearchResponse>()

        val jsonSearch = tryParseJson<SearchJson>(response)
        if (!jsonSearch?.results.isNullOrEmpty()) {
            jsonSearch?.results?.forEach { htmlItem ->
                val doc = Jsoup.parse(htmlItem)
                doc.selectFirst("a, div.search-item")?.toSearchResult()?.let { results.add(it) }
            }
            if (results.isNotEmpty()) return results
        }

        val doc = Jsoup.parse(response)
        return doc.select("a.poster, a.search-item, div.result-group a").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val ldJsonScript = document.selectFirst("script[type=application/ld+json]")?.data()
        val ldData       = tryParseJson<LdJson>(ldJsonScript)

        val rawTitle = ldData?.name
            ?: document.selectFirst("h1.section-title")?.ownText()?.replace("izle", "", ignoreCase = true)?.trim()
            ?: return null

        val subTitle = document.selectFirst("h1.section-title small")?.text() ?: ""
        val year     = Regex("""\((\d{4})\)""").find(subTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: ldData?.datePublished?.take(4)?.toIntOrNull()

        val poster = fixUrlNull(
            ldData?.image ?: document.selectFirst("div.post-info-poster img, img.poster")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        )

        val description = ldData?.description
            ?: document.selectFirst("article.post-info-content p, div.post-info-content")?.text()?.trim()

        val tags   = ldData?.genre ?: document.select("div.post-info-genres a").map { it.text().trim() }
        val actors = ldData?.actor?.mapNotNull { it.name } ?: emptyList()
        val imdbId = ldData?.sameAs?.firstOrNull { it.contains("imdb.com") }?.substringAfterLast("/")

        val episodeElements = document.select("div.seasons-tab-content a, div.episodes-list a, a.episode-item")
        if (episodeElements.isNotEmpty() || ldData?.type.equals("TVSeries", ignoreCase = true)) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epName = ep.text().trim()
                val seasonParent = ep.parents().firstOrNull { it.hasAttr("data-season") }
                val seasonNum    = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: seasonParent?.attr("data-season")?.toIntOrNull() ?: 1
                val episodeNum   = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val processedUrls = mutableSetOf<String>()

        document.selectFirst("div.video-container iframe")?.let { iframe ->
            val iframeSrc = fixUrlNull(iframe.attr("data-src").ifEmpty { iframe.attr("src") })
            if (iframeSrc != null && processedUrls.add(iframeSrc)) {
                extractVideo(iframeSrc, data, "Varsayılan", subtitleCallback, callback)
            }
        }

        document.select("button.alternative-link").forEach { button ->
            val videoId    = button.attr("data-video").ifEmpty { return@forEach }
            val langParent = button.parents().firstOrNull { it.hasClass("alternative-links") }
            val langCode   = langParent?.attr("data-lang")?.uppercase() ?: "TR"
            val altName    = button.text().trim()
            val label      = "$langCode - $altName"

            val responseText = runCatching {
                app.get(
                    "${mainUrl}/video/$videoId/",
                    headers = mapOf(
                        "X-Requested-With" to "fetch",
                        "Referer"          to data
                    )
                ).text
            }.getOrNull() ?: return@forEach

            val cleanHtml = responseText.replace("\\\"", "\"").replace("\\/", "/")
            val iframeSrc = Jsoup.parse(cleanHtml).selectFirst("iframe")?.let {
                fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") })
            } ?: Regex("""src=["'](https?://[^"']+)["']""").find(cleanHtml)?.groupValues?.get(1)

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
        if (loadExtractor(iframeUrl, referer, subtitleCallback, callback)) return

        runCatching {
            val embedResponse = app.get(iframeUrl, referer = referer)
            val embedDoc      = embedResponse.document
            val embedText     = embedResponse.text
            val embedHost     = Regex("""https?://[^/]+""").find(iframeUrl)?.value ?: mainUrl

            Regex("""["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["']""")
                .findAll(embedText).forEach { match ->
                    val subUrl  = fixUrl(match.groupValues[1], embedHost)
                    val subLang = match.groupValues[2]
                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                }

            val packedScript = embedDoc.select("script").firstOrNull {
                it.data().contains("eval(function(p,a,c,k,e,d)")
            }?.data()

            val unpacked = if (packedScript != null) getAndUnpack(packedScript) else embedText

            // 1. dc_hello Base64 şifrelemesi
            Regex("""dc_hello\(\[([^\]]+)\]\)|dc_hello\(["']([^"']+)["']\)""").find(unpacked)?.let { match ->
                val rawParts = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val joined   = rawParts.replace("\"", "").replace("'", "").replace(",", "").replace(" ", "")
                val decoded  = String(Base64.decode(joined, Base64.DEFAULT))
                val m3u8Url  = Regex("""https?://[^\s"']+\.(?:m3u8|txt)[^\s"']*""").find(decoded)?.value ?: decoded

                if (m3u8Url.startsWith("http")) {
                    M3u8Helper.generateM3u8(
                        source  = "${this.name} ($sourceLabel)",
                        streamUrl = m3u8Url,
                        referer = iframeUrl
                    ).forEach(callback)
                    return
                }
            }

            // 2. Doğrudan file / src m3u8 bağlantısı
            Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.let { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                M3u8Helper.generateM3u8(
                    source    = "${this.name} ($sourceLabel)",
                    streamUrl = m3u8Url,
                    referer   = iframeUrl
                ).forEach(callback)
            }
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
        @JsonProperty("sameAs") val sameAs: StringListDeserializer? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("actor") val actor: List<LdActor>? = null
    )

    data class LdActor(
        @JsonProperty("name") val name: String? = null
    )
}
