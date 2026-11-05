package com.liderfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LiderFilmProvider : MainAPI() {
    override var mainUrl = "https://liderfilmizle.vip"
    override var name = "LiderFilm"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "home" to "Öne Çıkanlar",
        "diziler" to "Tüm Diziler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7"
    )

    private suspend fun getDocument(url: String): Document =
        app.get(fixUrl(url), headers = requestHeaders).document

    private fun parseYear(value: String?): Int? =
        Regex("(19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()

    private fun parseScore(value: String?): Score? =
        value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }?.let { Score.from10(it) }

    private fun cardToSearch(card: Element, isSeries: Boolean): SearchResponse? {
        val href = card.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = if (isSeries) {
            card.selectFirst(".series-title")?.text()
        } else {
            card.selectFirst(".movie-title")?.text()
        } ?: card.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
        ?: return null

        val image = card.select("img")
        val poster: String? = (
            image.attr("src").ifBlank {
                image.attr("data-src")
            }
        ).takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }

        val yearText = if (isSeries) card.selectFirst(".year")?.text()
        else card.selectFirst(".movie-year")?.text()
        val ratingText = if (isSeries) card.selectFirst(".imdb")?.text()
        else card.selectFirst(".movie-rating-inline")?.text()

        val fixedUrl = fixUrl(href)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries, fix = false) {
                posterUrl = poster
                year = parseYear(yearText)
                score = parseScore(ratingText)
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, TvType.Movie, fix = false) {
                posterUrl = poster
                year = parseYear(yearText)
                score = parseScore(ratingText)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val target = request.data
        val url = when (target) {
            "diziler" -> "$mainUrl/diziler?page=$page"
            else -> if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        }
        val document = getDocument(url)
        val isSeriesPage = target == "diziler"
        val cards = if (isSeriesPage) {
            document.select("a.series-card")
        } else {
            document.select("a.movie-card, a.series-card")
        }
        val items = cards.mapNotNull { card ->
            val series = card.hasClass("series-card")
            cardToSearch(card, series)
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = app.get("$mainUrl/api/search.php?q=$encoded", headers = requestHeaders).text
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val output = ArrayList<SearchResponse>()

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val slug = item.optString("slug").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val title = Jsoup.parse(item.optString("title")).text().takeIf { it.isNotBlank() } ?: continue
            val type = item.optString("type").lowercase()
            val poster = item.optString("poster").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val year = item.optString("year").toIntOrNull()
            val score = parseScore(item.optString("imdb_score"))
            val href = if (type == "series") "$mainUrl/dizi/$slug" else "$mainUrl/$slug"

            when (type) {
                "series" -> output += newTvSeriesSearchResponse(title, href, TvType.TvSeries, fix = false) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                }
                "movie" -> output += newMovieSearchResponse(title, href, TvType.Movie, fix = false) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                }
            }
        }
        return output
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val canonical = document.selectFirst("link[rel=canonical]")?.absUrl("href")
            ?.takeIf { it.startsWith(mainUrl) } ?: url
        val jsonLd = document.select("script[type=application/ld+json]")
            .firstOrNull { it.data().contains("Movie") || it.data().contains("TVSeries") }
            ?.data()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim().ifBlank { return null }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val plot = jsonLd?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val year = parseYear(jsonLd?.optString("datePublished"))
        val tags = mutableListOf<String>()
        val genre = jsonLd?.opt("genre")
        when (genre) {
            is org.json.JSONArray -> for (i in 0 until genre.length()) genre.optString(i).takeIf { it.isNotBlank() }?.let(tags::add)
            is String -> tags += genre
        }

        val isSeries = canonical.contains("/dizi/") && !Regex("/sezon-\\d+/bolum-\\d+").containsMatchIn(canonical)
        if (isSeries) {
            val episodes = document.select(".episode-card").mapNotNull { card ->
                val link = card.selectFirst("a.episode-thumb") ?: return@mapNotNull null
                val episodeUrl = link.absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return@mapNotNull null
                val episodeName = card.selectFirst(".episode-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Bölüm"
                val numberText = card.selectFirst(".episode-number")?.text().orEmpty()
                val episodeNumber = Regex("(\\d+)").find(numberText)?.groupValues?.get(1)?.toIntOrNull()
                val seasonNumber = Regex("/sezon-(\\d+)/").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodePoster = link.selectFirst("img")?.absUrl("src")?.takeIf { it.startsWith("http") }
                val description = card.selectFirst(".episode-desc")?.text()
                
                newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = episodePoster
                    this.description = description
                }
            }

            return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
                score = parseScore(document.selectFirst(".imdb")?.text())
            }
        }

        val recommendations = document.select(".movie-card").mapNotNull { card -> cardToSearch(card, false) }
        return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags.takeIf { it.isNotEmpty() }
            score = parseScore(document.selectFirst(".movie-rating-inline")?.text())
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)

        val iframeUrl = document.selectFirst("iframe.player-iframe, iframe[src*='player'], iframe[src*='embed'], .video-player iframe")?.absUrl("src")
            ?: document.selectFirst("iframe")?.absUrl("src")
            ?: return false

        loadExtractor(iframeUrl, data, subtitleCallback, callback)

        return true
    }
}
