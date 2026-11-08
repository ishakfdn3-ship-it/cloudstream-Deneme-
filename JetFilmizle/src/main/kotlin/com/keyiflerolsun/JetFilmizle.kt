package com.kullanici.jetfilmizle

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
    override var name = "JetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Yeni Eklenen Filmler",
        "$mainUrl/diziler" to "Son Eklenen Diziler",
        "$mainUrl/turkce-dublaj" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/nette-ilkler" to "Nette İlkler",
        "$mainUrl/filmler/en-cok-izlenenler" to "En Çok İzlenenler",
        "$mainUrl/imdb-top" to "IMDb Top 250"
    )

    data class SourceVariant(
        @JsonProperty("playerType") val playerType: String,
        @JsonProperty("sourceIndex") val sourceIndex: Int,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class LoadData(
        @JsonProperty("pageUrl") val pageUrl: String,
        @JsonProperty("filmId") val filmId: String? = null,
        @JsonProperty("variants") val variants: List<SourceVariant> = emptyList()
    )

    // Arama API'si JSON modelleri
    data class SearchApiData(
        @JsonProperty("results") val results: List<SearchApiItem>? = null
    )

    data class SearchApiItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("rating") val rating: String? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/sayfa-$page"
        val doc = app.get(url, referer = "$mainUrl/").document

        val items = doc.select("div.film-card.jf-card").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("ul.pagination-modern li.next-page a.page-link-modern[href], a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (selectFirst(".fc-chip--vizyon, .fc-chip--yakinda") != null) return null

        val linkEl = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = selectFirst("h3.fc-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val img = selectFirst("div.film-poster img, img.card-img-top, img")
        val poster = fixUrlNull(
            listOf("data-src", "data-lazy-src", "src")
                .map { img?.attr(it).orEmpty() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        )

        val yearText = selectFirst("span.fc-year")?.text()?.trim()
        val yearVal = yearText?.toIntOrNull()
        val scoreText = selectFirst("span.fc-rating--imdb")?.text()?.trim()

        val isSeries = href.contains("/dizi/") || selectFirst(".fc-chip--dizi") != null
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yearVal
                this.score = Score.from10(scoreText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = yearVal
                this.score = Score.from10(scoreText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "$mainUrl/arama-json?q=$encoded"
        
        val apiResponse = app.get(
            apiUrl,
            headers = mapOf(
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        ).parsedSafe<SearchApiData>()

        return apiResponse?.results?.mapNotNull { item ->
            val isSeries = item.type.equals("dizi", ignoreCase = true)
            val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = item.url?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(item.poster)
            val yearVal = item.year?.toIntOrNull()
            val scoreVal = Score.from10(item.rating)

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = yearVal
                    this.score = scoreVal
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = yearVal
                    this.score = scoreVal
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1.film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=film:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(doc.selectFirst("section.film-bilgileri-section img.film-poster")?.attr("src"))
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val backgroundPoster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content")) ?: poster

        val plot = doc.selectFirst("div.film-description div.description-text")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = doc.selectFirst("meta[name=film:year]")?.attr("content")?.toIntOrNull()
            ?: doc.selectFirst("ul.jf-detay-kutular a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()

        val duration = doc.selectFirst("meta[name=film:duration]")?.attr("content")?.toIntOrNull()

        val scoreText = doc.selectFirst("a.rating-badge.imdb b")?.text()?.trim()
            ?: doc.selectFirst("meta[name=film:rating]")?.attr("content")?.takeIf { it != "0.0" }

        val tags = doc.select("div.categories-container-details a.category-badge-details")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = doc.select("div.actors-grid div.cast-card").mapNotNull { card ->
            val actorName = card.selectFirst("div.cast-info .h6 a, div.cast-info a")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val actorImg = fixUrlNull(card.selectFirst("div.cast-avatar img")?.attr("src"))
            val roleName = card.selectFirst("div.karakter-badge")?.text()?.trim()?.takeIf { it.isNotBlank() }
            Actor(actorName, actorImg) to roleName
        }

        val trailerUrl = fixUrlNull(
            doc.selectFirst("iframe#trailerIframe")?.attr("data-src")?.takeIf { it.isNotBlank() }
        )

        val recommendations = doc.select("div.similar-films-track div.similar-film-item div.film-card.jf-card")
            .mapNotNull { it.toSearchResult() }

        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.takeIf { it.isNotBlank() }

        val sectionMeta = doc.selectFirst("meta[property=article:section]")?.attr("content").orEmpty()
        val episodeButtons = doc.select("div.player-sources button.episode-btn.player-source-btn[data-season][data-episode]")
        val isSeries = url.contains("/dizi/") || sectionMeta.equals("Diziler", ignoreCase = true) || episodeButtons.isNotEmpty()

        if (isSeries && episodeButtons.isNotEmpty()) {
            val groupedVariants = linkedMapOf<Pair<Int, Int>, MutableList<SourceVariant>>()
            for (btn in episodeButtons) {
                val sNum = btn.attr("data-season").toIntOrNull() ?: continue
                val eNum = btn.attr("data-episode").toIntOrNull() ?: continue
                val srcIdx = btn.attr("data-source-index").toIntOrNull() ?: continue
                val pType = btn.attr("data-player-type").ifBlank { "dublaj" }
                val key = sNum to eNum
                groupedVariants.getOrPut(key) { mutableListOf() }.add(
                    SourceVariant(
                        playerType = pType,
                        sourceIndex = srcIdx,
                        season = sNum,
                        episode = eNum
                    )
                )
            }

            val episodes = groupedVariants.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { (seasonEp, variants) ->
                    val (sNum, eNum) = seasonEp
                    val epData = LoadData(
                        pageUrl = url,
                        filmId = filmId,
                        variants = variants
                    ).toJson()
                    newEpisode(epData) {
                        this.name = "$sNum. Sezon $eNum. Bölüm"
                        this.season = sNum
                        this.episode = eNum
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = Score.from10(scoreText)
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            val movieVariants = doc.select("div.player-sources button.player-source-btn, div.player-sources button[data-player-type]")
                .mapNotNull { btn ->
                    val pType = btn.attr("data-player-type").ifBlank { "dublaj" }
                    val srcIdx = btn.attr("data-source-index").toIntOrNull() ?: 0
                    SourceVariant(playerType = pType, sourceIndex = srcIdx)
                }.distinct()

            val movieData = LoadData(
                pageUrl = url,
                filmId = filmId,
                variants = movieVariants
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = Score.from10(scoreText)
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = tryParseJson<LoadData>(data)
        val pageUrl = parsed?.pageUrl ?: data
        var found = false

        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        
        val directEmbeds = extractValidEmbeds(doc)
        for (embed in directEmbeds) {
            try {
                if (loadExtractor(embed, pageUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (parsed?.filmId != null && parsed.variants.isNotEmpty()) {
            for (variant in parsed.variants) {
                try {
                    val bodyData = mapOf(
                        "film_id" to parsed.filmId,
                        "source_index" to variant.sourceIndex.toString(),
                        "player_type" to variant.playerType
                    )
                    
                    val responseText = app.post(
                        "$mainUrl/jetplayer",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        ),
                        data = bodyData,
                        referer = pageUrl
                    ).text

                    val iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                        .find(responseText)?.groupValues?.get(1)?.replace("&amp;", "&")
                        ?: continue

                    val cleanEmbedUrl = fixUrlNull(iframeUrl) ?: continue
                    if (!cleanEmbedUrl.contains("youtube.com/embed/") && 
                        !cleanEmbedUrl.contains("wargamings.net")) {
                        
                        if (loadExtractor(cleanEmbedUrl, pageUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        return found
    }

    private fun extractValidEmbeds(doc: Document): List<String> {
        return doc.select("#active-player iframe[src], .film-player iframe[src], .film-player iframe[data-src]")
            .mapNotNull { iframe ->
                val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                fixUrlNull(raw)
            }
            .filter { url ->
                !url.contains("youtube.com/embed/") &&
                !url.contains("wargamings.net") &&
                !url.contains("google.com")
            }
            .distinct()
    }
}
