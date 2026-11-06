package com.filmmodu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmModuProvider : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7"
    )

    private suspend fun getDocument(url: String): Document =
        app.get(fixUrl(url), headers = requestHeaders).document

    private fun parseYear(value: String?): Int? =
        Regex("(19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()

    private fun cardToSearch(card: Element, isSeries: Boolean): SearchResponse? {
        val href = card.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst(".title, h3, .film-title")?.text() 
            ?: card.selectFirst("img")?.attr("alt") 
            ?: return null

        val image = card.select("img")
        val poster: String? = (
            image.attr("src").ifBlank {
                image.attr("data-src")
            }
        ).takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }

        val fixedUrl = fixUrl(href)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries, fix = false) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, TvType.Movie, fix = false) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}?page=$page"
        val document = getDocument(url)
        val cards = document.select("div.film-card, article.movie-item, a.item-link, .poster-container a")
        
        val items = cards.mapNotNull { card ->
            val href = card.attr("href")
            val isSeries = href.contains("/dizi")
            cardToSearch(card, isSeries)
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = getDocument("$mainUrl/arama?q=$encoded")
        
        return document.select("div.film-card, article.movie-item, .search-result-item a").mapNotNull { card ->
            val href = card.attr("href")
            val isSeries = href.contains("/dizi")
            cardToSearch(card, isSeries)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val year = parseYear(document.selectFirst(".release-year, .year")?.text())

        val isSeries = url.contains("/dizi")
        if (isSeries) {
            val episodes = document.select("ul.episodes-list li a, .episode-item a").mapNotNull { link ->
                val episodeUrl = link.absUrl("href")
                val episodeName = link.text().trim()
                val seasonNumber = Regex("sezon-(\\d+)").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNumber = Regex("bolum-(\\d+)").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)

        val iframeUrl = document.selectFirst("iframe[src*='vidmixi'], iframe[src*='embed'], iframe[src*='player'], .player-box iframe")?.absUrl("src")
            ?: document.selectFirst("iframe")?.absUrl("src")
            ?: return false

        if (iframeUrl.contains("vidmixi") || iframeUrl.contains("embed")) {
            val embedHtml = app.get(iframeUrl, referer = data, headers = requestHeaders).text
            
            val m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(embedHtml)
                ?: Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(embedHtml)

            val streamUrl = m3u8Match?.groupValues?.get(1)?.replace("\\/", "/")

            if (!streamUrl.isNullOrBlank()) {
                val headers = mapOf(
                    "Origin" to "https://vidmixi.com",
                    "Referer" to "$iframeUrl",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = streamUrl,
                    referer = iframeUrl,
                    headers = headers
                ).forEach(callback)

                return true
            }
        }

        loadExtractor(iframeUrl, data, subtitleCallback, callback)
        return true
    }
}
