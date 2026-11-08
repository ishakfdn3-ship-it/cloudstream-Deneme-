package com.keyiflerolsun

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

@CloudstreamPlugin
class JetFilmizlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JetFilmizle())
    }
}

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
    override var name = "JetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Yeni Eklenen Filmler",
        "$mainUrl/nette-ilkler" to "Nette İlkler",
        "$mainUrl/turkce-dublaj" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/diziler" to "Son Eklenen Diziler",
        "$mainUrl/yerli-diziler" to "Yerli Diziler",
        "$mainUrl/yabanci-diziler" to "Yabancı Diziler",
        "$mainUrl/imdb-top" to "IMDb Top 250"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/sayfa-$page"
        val doc = app.get(url, referer = "$mainUrl/").document

        val items = doc.select("div.film-card.jf-card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = doc.selectFirst(".pagination-modern .next-page a[href], .pagination-modern a[rel=next]") != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = selectFirst("h3.fc-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val img = selectFirst(".film-poster img, img.card-img-top")
        val poster = fixUrlNull(
            listOf("data-src", "src")
                .map { img?.attr(it).orEmpty() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        )

        val year = selectFirst(".fc-year")?.text()?.trim()?.toIntOrNull()
        val scoreText = selectFirst(".fc-rating--imdb")?.text()?.trim()

        val isSeries = href.contains("/dizi/") || selectFirst(".fc-chip--dizi") != null
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(scoreText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(scoreText)
            }
        }
    }

    data class SearchJsonResult(
        @JsonProperty("results") val results: List<SearchJsonItem>? = null
    )

    data class SearchJsonItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        try {
            val jsonText = app.get(
                "$mainUrl/arama-json?q=$encoded",
                referer = "$mainUrl/",
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            val parsed = tryParseJson<SearchJsonResult>(jsonText)
            parsed?.results?.forEach { item ->
                val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val href = fixUrlNull(item.url) ?: return@forEach
                val poster = fixUrlNull(item.poster?.takeIf { !it.contains("placeholder") })
                val year = item.year?.trim()?.toIntOrNull()
                val isSeries = item.type.equals("dizi", ignoreCase = true) || href.contains("/dizi/")
                if (isSeries) {
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = Score.from10(item.rating)
                    })
                } else {
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = Score.from10(item.rating)
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
        }

        if (results.isNotEmpty()) return results

        val doc = app.get("$mainUrl/arama?q=$encoded", referer = "$mainUrl/").document
        return doc.select("div.film-card.jf-card").mapNotNull { it.toSearchResult() }
    }

    data class SourceVariant(
        @JsonProperty("sourceIndex") val sourceIndex: String,
        @JsonProperty("playerType") val playerType: String,
        @JsonProperty("label") val label: String
    )

    data class LoadLinkData(
        @JsonProperty("pageUrl") val pageUrl: String,
        @JsonProperty("filmId") val filmId: String,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("isSeries") val isSeries: Boolean = false,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("variants") val variants: List<SourceVariant> = emptyList()
    )

    data class JetEmbedApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("sources") val sources: List<JetEmbedSource>? = null
    )

    data class JetEmbedSource(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("priority_score") val priorityScore: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1.film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=film:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst(".film-hero-card img.film-poster")?.attr("src")
                ?.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }
        )
        val backgroundPoster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content")) ?: poster

        val plot = doc.selectFirst(".film-description .description-text")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty() }

        val year = doc.selectFirst("meta[name=film:year]")?.attr("content")?.toIntOrNull()
            ?: doc.selectFirst("meta[property=video:release_date]")?.attr("content")?.toIntOrNull()

        val duration = doc.selectFirst("meta[name=film:duration]")?.attr("content")?.toIntOrNull()
        val rating = doc.selectFirst("meta[name=film:rating]")?.attr("content")
            ?: doc.selectFirst(".rating-badge.imdb b")?.text()

        val imdbId = doc.selectFirst("meta[name=film:imdb_id]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.trim().orEmpty()

        val tags = doc.select(".categories-container-details a.category-badge-details")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { doc.select("meta[property=video:tag]").mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } } }

        val actors = doc.select(".actors-grid .cast-card").mapNotNull { card ->
            val actorName = card.selectFirst(".cast-info a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val avatar = fixUrlNull(card.selectFirst(".cast-avatar img")?.attr("src"))
            val role = card.selectFirst(".karakter-badge")?.text()?.trim()?.takeIf { it.isNotBlank() }
            Actor(actorName, avatar) to role
        }

        val trailer = fixUrlNull(doc.selectFirst("#trailerIframe")?.attr("data-src"))
        val recommendations = doc.select(".similar-film-item div.film-card.jf-card").mapNotNull { it.toSearchResult() }

        val episodeButtons = doc.select(".episode-btn.player-source-btn")
        val sectionMeta = doc.selectFirst("meta[property=article:section]")?.attr("content").orEmpty()
        val isSeries = url.contains("/dizi/") || episodeButtons.isNotEmpty() || sectionMeta.equals("Diziler", ignoreCase = true)

        if (isSeries) {
            val groupedEpisodes = LinkedHashMap<Pair<Int, Int>, MutableList<SourceVariant>>()
            for (btn in episodeButtons) {
                val s = btn.attr("data-season").toIntOrNull() ?: 1
                val e = btn.attr("data-episode").toIntOrNull() ?: continue
                val srcIdx = btn.attr("data-source-index").takeIf { it.isNotBlank() } ?: continue
                val pType = btn.attr("data-player-type").takeIf { it.isNotBlank() } ?: "genel"
                val key = s to e
                val list = groupedEpisodes.getOrPut(key) { mutableListOf() }
                val label = playerTypeDisplayName(pType, btn.text().trim())
                if (list.none { it.sourceIndex == srcIdx && it.playerType == pType }) {
                    list.add(SourceVariant(srcIdx, pType, label))
                }
            }

            val episodes = groupedEpisodes.map { (sePair, variants) ->
                val (s, e) = sePair
                val epData = LoadLinkData(
                    pageUrl = url,
                    filmId = filmId,
                    imdbId = imdbId,
                    isSeries = true,
                    season = s,
                    episode = e,
                    variants = variants
                ).toJson()

                newEpisode(epData) {
                    this.name = "$s. Sezon $e. Bölüm"
                    this.season = s
                    this.episode = e
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val variants = doc.select(".player-source-btn:not(.episode-btn)").mapNotNull { btn ->
                val srcIdx = btn.attr("data-source-index").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pType = btn.attr("data-player-type").takeIf { it.isNotBlank() } ?: "genel"
                val btnName = btn.text().trim().ifBlank { "Kaynak ${srcIdx.toIntOrNull()?.plus(1) ?: 1}" }
                SourceVariant(srcIdx, pType, "${playerTypeDisplayName(pType, "")} - $btnName".trim(' ', '-'))
            }

            val movieData = LoadLinkData(
                pageUrl = url,
                filmId = filmId,
                imdbId = imdbId,
                isSeries = false,
                variants = variants
            ).toJson()

            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun playerTypeDisplayName(playerType: String, fallback: String): String {
        return when (playerType.lowercase()) {
            "dublaj" -> "Türkçe Dublaj"
            "altyazili" -> "Türkçe Altyazılı"
            "genel" -> "Orijinal / Dil+"
            else -> fallback.ifBlank { playerType }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = if (data.trim().startsWith("{")) {
            tryParseJson<LoadLinkData>(data)
        } else {
            null
        } ?: fetchFallbackLinkData(data) ?: return false

        var foundAny = false
        val variantsToTry = linkData.variants.ifEmpty {
            listOf(
                SourceVariant("0", "dublaj", "Türkçe Dublaj"),
                SourceVariant("0", "altyazili", "Türkçe Altyazılı"),
                SourceVariant("0", "genel", "Orijinal / Dil+")
            )
        }

        for (variant in variantsToTry) {
            try {
                if (linkData.filmId.isNotBlank()) {
                    val responseText = app.post(
                        "$mainUrl/jetplayer",
                        referer = linkData.pageUrl,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl
                        ),
                        data = mapOf(
                            "film_id" to linkData.filmId,
                            "source_index" to variant.sourceIndex,
                            "player_type" to variant.playerType
                        )
                    ).text

                    if (handlePlayerHtmlResponse(
                            html = responseText,
                            referer = linkData.pageUrl,
                            variant = variant,
                            linkData = linkData,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        foundAny = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (!foundAny && !linkData.imdbId.isNullOrBlank()) {
            val defaultVariant = variantsToTry.first()
            val directJetEmbedUrl = "https://videopark.top/jetembed/${linkData.imdbId}"
            try {
                if (extractJetEmbed(directJetEmbedUrl, linkData.pageUrl, defaultVariant, linkData, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return foundAny
    }

    private suspend fun fetchFallbackLinkData(pageUrl: String): LoadLinkData? {
        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.trim().orEmpty()
        val imdbId = doc.selectFirst("meta[name=film:imdb_id]")?.attr("content")?.trim()
        val variants = doc.select(".player-source-btn").mapNotNull { btn ->
            val srcIdx = btn.attr("data-source-index").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pType = btn.attr("data-player-type").takeIf { it.isNotBlank() } ?: "genel"
            SourceVariant(srcIdx, pType, "${playerTypeDisplayName(pType, "")} - ${btn.text().trim()}")
        }
        return LoadLinkData(pageUrl = pageUrl, filmId = filmId, imdbId = imdbId, variants = variants)
    }

    private suspend fun handlePlayerHtmlResponse(
        html: String,
        referer: String,
        variant: SourceVariant,
        linkData: LoadLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val frag: Document = Jsoup.parseBodyFragment(html, mainUrl)

        for (srcEl in frag.select("video[src], video source[src]")) {
            val videoUrl = fixUrlNull(srcEl.attr("src")) ?: continue
            emitDirectLink(videoUrl, referer, variant.label, callback)
            found = true
        }

        val iframeUrls = frag.select("iframe[src], iframe[data-src]")
            .filterNot { it.id() == "yt-frame" || it.id() == "trailerIframe" }
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { it.contains("youtube.com/embed/") || it.contains("youtu.be/") }
            .distinct()

        for (iframeUrl in iframeUrls) {
            try {
                if (iframeUrl.contains("/jetembed/")) {
                    if (extractJetEmbed(iframeUrl, referer, variant, linkData, subtitleCallback, callback)) {
                        found = true
                    }
                } else if (loadExtractor(iframeUrl, referer, subtitleCallback, callback)) {
                    found = true
                } else if (scrapeGenericEmbedPage(iframeUrl, referer, variant.label, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return found
    }

    private suspend fun extractJetEmbed(
        embedUrl: String,
        pageReferer: String,
        variant: SourceVariant,
        linkData: LoadLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val embedHtml = app.get(embedUrl, referer = pageReferer).text

        val initRegex = Regex(
            """player\.init\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*([^,)]+)\s*,\s*([^)]+)\)"""
        )
        val match = initRegex.find(embedHtml)
        val imdbId = match?.groupValues?.get(1)?.trim()
            ?: linkData.imdbId
            ?: embedUrl.substringAfterLast("/").substringBefore("?")
        val mediaType = match?.groupValues?.get(2)?.trim()
            ?: if (linkData.isSeries) "tv" else "movie"
        val seasonArg = match?.groupValues?.get(3)?.trim()?.trim('\'', '"')?.takeIf { it != "null" }
            ?: linkData.season?.toString()
        val episodeArg = match?.groupValues?.get(4)?.trim()?.trim('\'', '"')?.takeIf { it != "null" }
            ?: linkData.episode?.toString()

        val uri = URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"

        val queryParams = mutableListOf("imdb=${URLEncoder.encode(imdbId, "UTF-8")}")
        if (mediaType.equals("tv", ignoreCase = true) || seasonArg != null) {
            queryParams.add("type=${URLEncoder.encode(mediaType, "UTF-8")}")
            if (seasonArg != null) queryParams.add("season=${URLEncoder.encode(seasonArg, "UTF-8")}")
            if (episodeArg != null) queryParams.add("episode=${URLEncoder.encode(episodeArg, "UTF-8")}")
        }
        val dilParam = Regex("""[?&]dil=([^&#]+)""").find(embedUrl)?.groupValues?.get(1)
        if (!dilParam.isNullOrBlank()) {
            queryParams.add("dil=${URLEncoder.encode(dilParam, "UTF-8")}")
        }

        val apiUrl = "$origin/jetembed/backend/api_sources.php?${queryParams.joinToString("&")}"
        val apiJsonText = app.get(
            apiUrl,
            referer = embedUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/plain, */*"
            )
        ).text

        val parsed = tryParseJson<JetEmbedApiResponse>(apiJsonText)
        val sources = parsed?.sources?.sortedByDescending { it.priorityScore ?: 0 }.orEmpty()

        for (src in sources) {
            val targetUrl = src.url?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val sourceLabel = "${variant.label} [${src.name ?: "JetEmbed"}]"
            try {
                if (loadExtractor(targetUrl, embedUrl, subtitleCallback, callback)) {
                    found = true
                } else if (scrapeGenericEmbedPage(targetUrl, embedUrl, sourceLabel, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return found
    }

    private suspend fun scrapeGenericEmbedPage(
        embedUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(embedUrl, referer = referer)
        val body = resp.text
        var found = parseLinksFromRawText(body, embedUrl, label, subtitleCallback, callback)

        val nestedIframes = resp.document.select("iframe[src], iframe[data-src]")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { it.contains("youtube.com") || it == embedUrl }
            .distinct()

        for (nested in nestedIframes) {
            try {
                if (loadExtractor(nested, embedUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
        return found
    }

    private suspend fun parseLinksFromRawText(
        rawText: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val unescaped = rawText.replace("""\/""", "/")

        val subRegex = Regex("""https?://[^\s"'<>]+?\.(?:vtt|srt)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        for (subMatch in subRegex.findAll(unescaped)) {
            subtitleCallback(SubtitleFile("Türkçe", subMatch.value))
        }

        val streamRegex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        for (m in streamRegex.findAll(unescaped)) {
            emitDirectLink(m.value, referer, label, callback)
            found = true
        }

        return found
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = url.contains(".m3u8", ignoreCase_ = true) // Wait, ignoreCase argument is lowercase ignoreCase
        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $label",
                url = url,
                type = linkType
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
