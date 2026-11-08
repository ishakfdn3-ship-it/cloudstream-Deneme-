package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
    override var name = "JetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 1_Ana_Sayfa_GET_ve_HTML.html ve E04_diziler.html içinde doğrulandı[span_0](start_span)[span_0](end_span)[span_1](start_span)[span_1](end_span)
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

        // 1. Öncelik: E09_js_search.js içinde doğrulanan /arama-json?q=[span_2](start_span)[span_2](end_span)
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
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("isSeries") val isSeries: Boolean = false,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("variants") val variants: List<SourceVariant> = emptyList()
    )

    // E03_dizi_ham.html içinde doğrulanan JSON-LD dizi/sezon/bölüm modeli[span_3](start_span)[span_3](end_span)
    data class JsonLdSeries(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("containsSeason") val containsSeason: List<JsonLdSeason>? = null
    )

    data class JsonLdSeason(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("episode") val episodes: List<JsonLdEpisode>? = null
    )

    data class JsonLdEpisode(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null
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
            ?: Regex("""imdb\.com/title/(tt\d+)""").find(doc.html())?.groupValues?.get(1)

        // E03_dizi_ham.html içinde doğrulandı: <a href='https://www.themoviedb.org/tv/102621' class='rating-badge tmdb'>[span_4](start_span)[span_4](end_span)
        val tmdbId = Regex("""themoviedb\.org/(?:tv|movie)/(\d+)""").find(doc.html())?.groupValues?.get(1)

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

        // Dizi kontrolü (E03_dizi_ham.html ve 4_Dizi_Detay_Sayfasi.html)[span_5](start_span)[span_5](end_span)[span_6](start_span)[span_6](end_span)
        val episodeButtons = doc.select(".episode-btn, button[data-episode], .player-source-btn[data-episode]")
        val sectionMeta = doc.selectFirst("meta[property=article:section]")?.attr("content").orEmpty()
        val isSeries = url.contains("/dizi/") || episodeButtons.isNotEmpty() || sectionMeta.equals("Diziler", ignoreCase = true)

        if (isSeries) {
            val groupedEpisodes = LinkedHashMap<Pair<Int, Int>, MutableList<SourceVariant>>()

            // 1. Yöntem: HTML içindeki .episode-btn butonlarını topla (dublaj, altyazili, genel)[span_7](start_span)[span_7](end_span)[span_8](start_span)[span_8](end_span)
            for (btn in episodeButtons) {
                val s = btn.attr("data-season").toIntOrNull()
                    ?: btn.closest(".episode-list")?.attr("data-season")?.toIntOrNull()
                    ?: 1
                val e = btn.attr("data-episode").toIntOrNull()
                    ?: Regex("""(\d+)""").find(btn.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                val srcIdx = btn.attr("data-source-index").ifBlank { (e - 1).coerceAtLeast(0).toString() }
                val pType = btn.attr("data-player-type")
                    .ifBlank { btn.closest(".player-sources-group")?.attr("data-player-group").orEmpty() }
                    .ifBlank { "dublaj" }

                val key = s to e
                val list = groupedEpisodes.getOrPut(key) { mutableListOf() }
                val label = playerTypeDisplayName(pType, btn.text().trim())
                if (list.none { it.sourceIndex == srcIdx && it.playerType == pType }) {
                    list.add(SourceVariant(srcIdx, pType, label))
                }
            }

            // 2. Yöntem (Yedek): HTML'de buton bulunamadıysa JSON-LD içindeki containsSeason dizisinden bölümleri çıkar[span_9](start_span)[span_9](end_span)[span_10](start_span)[span_10](end_span)
            if (groupedEpisodes.isEmpty()) {
                val availableTypes = doc.select(".player-type-btn")
                    .mapNotNull { it.attr("data-player-type").takeIf { t -> t.isNotBlank() } }
                    .ifEmpty { listOf("dublaj", "altyazili", "genel") }

                for (scriptEl in doc.select("script[type=application/ld+json]")) {
                    val ld = tryParseJson<JsonLdSeries>(scriptEl.data()) ?: continue
                    if (!ld.type.equals("TVSeries", ignoreCase = true)) continue
                    ld.containsSeason?.forEach { seasonObj ->
                        val sNum = seasonObj.seasonNumber ?: 1
                        seasonObj.episodes?.forEachIndexed { idx, epObj ->
                            val eNum = epObj.episodeNumber ?: (idx + 1)
                            val key = sNum to eNum
                            val list = groupedEpisodes.getOrPut(key) { mutableListOf() }
                            for (pType in availableTypes) {
                                list.add(SourceVariant(idx.toString(), pType, playerTypeDisplayName(pType, "E$eNum")))
                            }
                        }
                    }
                }
            }

            val episodes = groupedEpisodes.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { (sePair, variants) ->
                    val (s, e) = sePair
                    val epData = LoadLinkData(
                        pageUrl = url,
                        filmId = filmId,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
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
                tmdbId = tmdbId,
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
            val defaultIdx = if (linkData.isSeries && linkData.episode != null) {
                (linkData.episode - 1).coerceAtLeast(0).toString()
            } else {
                "0"
            }
            listOf(
                SourceVariant(defaultIdx, "dublaj", "Türkçe Dublaj"),
                SourceVariant(defaultIdx, "altyazili", "Türkçe Altyazılı"),
                SourceVariant(defaultIdx, "genel", "Orijinal / Dil+")
            )
        }

        // 1. Adım: Her dil/kaynak varyantı için POST /jetplayer isteği gönder[span_11](start_span)[span_11](end_span)
        for (variant in variantsToTry) {
            try {
                if (linkData.filmId.isNotBlank()) {
                    val postMap = mutableMapOf(
                        "film_id" to linkData.filmId,
                        "source_index" to variant.sourceIndex,
                        "player_type" to variant.playerType
                    )
                    if (linkData.isSeries) {
                        linkData.season?.let { postMap["season"] = it.toString() }
                        linkData.episode?.let { postMap["episode"] = it.toString() }
                    }

                    val responseText = app.post(
                        "$mainUrl/jetplayer",
                        referer = linkData.pageUrl,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl
                        ),
                        data = postMap
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

        // 2. Adım: Dizi veya Filmde ek/yedek olarak doğrudan VideoPark / JetEmbed çöz[span_12](start_span)[span_12](end_span)[span_13](start_span)[span_13](end_span)[span_14](start_span)[span_14](end_span)
        if (!linkData.imdbId.isNullOrBlank()) {
            val querySuffix = buildString {
                val params = mutableListOf<String>()
                if (linkData.isSeries) {
                    params.add("type=tv")
                    linkData.season?.let { params.add("season=$it") }
                    linkData.episode?.let { params.add("episode=$it") }
                } else {
                    params.add("type=movie")
                }
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
            val directJetEmbedUrl = "https://videopark.top/jetembed/${linkData.imdbId}$querySuffix"
            try {
                if (VideoParkExtractor().extractWithContext(
                        url = directJetEmbedUrl,
                        referer = linkData.pageUrl,
                        labelPrefix = if (linkData.isSeries) "S${linkData.season ?: 1}E${linkData.episode ?: 1}" else "JetGlobal",
                        imdbOverride = linkData.imdbId,
                        tmdbOverride = linkData.tmdbId,
                        isSeries = linkData.isSeries,
                        seasonOverride = linkData.season,
                        episodeOverride = linkData.episode,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
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
        val tmdbId = Regex("""themoviedb\.org/(?:tv|movie)/(\d+)""").find(doc.html())?.groupValues?.get(1)
        val isSeries = pageUrl.contains("/dizi/")
        val variants = doc.select(".player-source-btn").mapNotNull { btn ->
            val srcIdx = btn.attr("data-source-index").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pType = btn.attr("data-player-type").takeIf { it.isNotBlank() } ?: "genel"
            SourceVariant(srcIdx, pType, "${playerTypeDisplayName(pType, "")} - ${btn.text().trim()}")
        }
        return LoadLinkData(
            pageUrl = pageUrl,
            filmId = filmId,
            imdbId = imdbId,
            tmdbId = tmdbId,
            isSeries = isSeries,
            season = if (isSeries) 1 else null,
            episode = if (isSeries) 1 else null,
            variants = variants
        )
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
            emitDirectLink(name, videoUrl, referer, variant.label, callback)
            found = true
        }

        // Sahte YouTube reklam iframe'i (#yt-frame) ve fragman (#trailerIframe) hariç tutulur[span_15](start_span)[span_15](end_span)[span_16](start_span)[span_16](end_span)
        val iframeUrls = frag.select("iframe[src], iframe[data-src]")
            .filterNot { it.id() == "yt-frame" || it.id() == "trailerIframe" }
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { it.contains("youtube.com/embed/") || it.contains("youtu.be/") }
            .distinct()

        for (iframeUrl in iframeUrls) {
            try {
                if (iframeUrl.contains("/jetembed/") || iframeUrl.contains("videopark.")) {
                    if (VideoParkExtractor().extractWithContext(
                            url = iframeUrl,
                            referer = referer,
                            labelPrefix = variant.label,
                            imdbOverride = linkData.imdbId,
                            tmdbOverride = linkData.tmdbId,
                            isSeries = linkData.isSeries,
                            seasonOverride = linkData.season,
                            episodeOverride = linkData.episode,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        found = true
                    }
                } else if (loadExtractor(iframeUrl, referer, subtitleCallback, callback)) {
                    found = true
                } else if (VideoParkExtractor().scrapeGenericEmbedPage(iframeUrl, referer, variant.label, subtitleCallback, callback)) {
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
}

/**
 * VideoParkExtractor (https://videopark.top/jetembed/...)[span_17](start_span)[span_17](end_span)[span_18](start_span)[span_18](end_span)[span_19](start_span)[span_19](end_span)
 */
open class VideoParkExtractor : ExtractorApi() {
    override val name = "VideoPark"
    override val mainUrl = "https://videopark.top"
    override val requiresReferer = true

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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithContext(
            url = url,
            referer = referer ?: "https://jetfilmizle.app/",
            labelPrefix = "JetEmbed",
            imdbOverride = null,
            tmdbOverride = null,
            isSeries = url.contains("type=tv") || url.contains("season="),
            seasonOverride = Regex("""[?&]season=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull(),
            episodeOverride = Regex("""[?&]episode=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull(),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    suspend fun extractWithContext(
        url: String,
        referer: String,
        labelPrefix: String,
        imdbOverride: String?,
        tmdbOverride: String?,
        isSeries: Boolean,
        seasonOverride: Int?,
        episodeOverride: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val embedHtml = try {
            app.get(url, referer = referer).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        // E10_embed0_orijinal.html'de doğrulandı: player.init('tt32741341', 'movie', null, null);[span_20](start_span)[span_20](end_span)[span_21](start_span)[span_21](end_span)[span_22](start_span)[span_22](end_span)
        val initRegex = Regex(
            """player\.init\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*([^,)]+)\s*,\s*([^)]+)\)"""
        )
        val match = initRegex.find(embedHtml)

        val imdbId = match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: imdbOverride
            ?: Regex("""(tt\d+)""").find(url)?.groupValues?.get(1)
            ?: return false

        val initType = match?.groupValues?.get(2)?.trim()
        val mediaType = when {
            isSeries -> "tv"
            initType != null && (initType.equals("tv", true) || initType.equals("series", true)) -> "tv"
            else -> "movie"
        }

        val seasonArg = seasonOverride?.toString()
            ?: match?.groupValues?.get(3)?.trim()?.trim('\'', '"')?.takeIf { it != "null" && it.isNotBlank() }
            ?: if (mediaType == "tv") "1" else null

        val episodeArg = episodeOverride?.toString()
            ?: match?.groupValues?.get(4)?.trim()?.trim('\'', '"')?.takeIf { it != "null" && it.isNotBlank() }
            ?: if (mediaType == "tv") "1" else null

        val uri = URI(url)
        val origin = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else mainUrl
        val dilParam = Regex("""[?&]dil=([^&#]+)""").find(url)?.groupValues?.get(1)

        // 2_Arama_HAR_Kaydi.json'da doğrulanan gerçek endpoint[span_23](start_span)[span_23](end_span)[span_24](start_span)[span_24](end_span)
        val queryParams = mutableListOf("imdb=${URLEncoder.encode(imdbId, "UTF-8")}")
        if (mediaType == "tv") {
            queryParams.add("type=tv")
            if (seasonArg != null) queryParams.add("season=${URLEncoder.encode(seasonArg, "UTF-8")}")
            if (episodeArg != null) queryParams.add("episode=${URLEncoder.encode(episodeArg, "UTF-8")}")
        }
        if (!dilParam.isNullOrBlank()) {
            queryParams.add("dil=${URLEncoder.encode(dilParam, "UTF-8")}")
        }

        val apiUrl = "$origin/jetembed/backend/api_sources.php?${queryParams.joinToString("&")}"
        val apiJsonText = try {
            app.get(
                apiUrl,
                referer = url,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        val parsed = tryParseJson<JetEmbedApiResponse>(apiJsonText)
        val sources = parsed?.sources?.sortedByDescending { it.priorityScore ?: 0 }.orEmpty()

        for (src in sources) {
            val rawTargetUrl = src.url?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val targetUrl = if (mediaType == "tv" && seasonArg != null && episodeArg != null) {
                adaptEmbedUrlForSeries(rawTargetUrl, imdbId, tmdbOverride, seasonArg, episodeArg)
            } else {
                rawTargetUrl
            }

            val sourceLabel = "$labelPrefix [${src.name ?: "Kaynak"}]"
            try {
                if (targetUrl.contains("vidrock.net")) {
                    if (VidRockExtractor().extractVidRock(targetUrl, url, sourceLabel, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                }
                if (loadExtractor(targetUrl, url, subtitleCallback, callback)) {
                    found = true
                } else if (scrapeGenericEmbedPage(targetUrl, url, sourceLabel, subtitleCallback, callback)) {
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

    private fun adaptEmbedUrlForSeries(
        rawUrl: String,
        imdbId: String,
        tmdbId: String?,
        season: String,
        episode: String
    ): String {
        if (rawUrl.contains("/tv/") || rawUrl.contains("/series/") || rawUrl.contains("season=")) {
            return rawUrl
        }
        val idToUse = tmdbId ?: imdbId
        return when {
            rawUrl.contains("vidrock.net/movie/") -> "https://vidrock.net/tv/$imdbId/$season/$episode"
            rawUrl.contains("vidlink.pro/movie/") -> "https://vidlink.pro/tv/$idToUse/$season/$episode"
            rawUrl.contains("vidfast.pro/movie/") -> "https://vidfast.pro/tv/$imdbId/$season/$episode"
            rawUrl.contains("moviesapi.to/movie/") -> "https://moviesapi.to/tv/$imdbId-$season-$episode"
            rawUrl.contains("airflix1.com/embed/movie/") -> "https://airflix1.com/embed/tv/$imdbId/$season/$episode"
            rawUrl.contains("vidking.net/embed/movie/") -> "https://www.vidking.net/embed/tv/$imdbId/$season/$episode"
            rawUrl.contains("play.xpass.top/e/movie/") -> "https://play.xpass.top/e/tv/$imdbId/$season/$episode"
            rawUrl.contains("player.videasy.net/movie/") -> {
                val vId = rawUrl.substringAfterLast("/movie/").substringBefore("?")
                "https://player.videasy.net/tv/$vId/$season/$episode"
            }
            rawUrl.contains("primesrc.me/embed/movie") -> {
                val qId = Regex("""tmdb=(\d+)""").find(rawUrl)?.groupValues?.get(1) ?: idToUse
                "https://primesrc.me/embed/tv?tmdb=$qId&season=$season&episode=$episode"
            }
            rawUrl.contains("peachify.top/embed/movie/") -> "https://peachify.top/embed/tv/$imdbId/$season/$episode"
            rawUrl.contains("/movie/$imdbId") -> rawUrl.replace("/movie/$imdbId", "/tv/$imdbId/$season/$episode")
            else -> rawUrl
        }
    }

    suspend fun scrapeGenericEmbedPage(
        embedUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(embedUrl, referer = referer)
        val body = resp.text
        var found = parseLinksFromRawText(body, embedUrl, label, subtitleCallback, callback)

        val unpacked = getAndUnpack(body)
        if (unpacked.isNotBlank() && unpacked != body) {
            if (parseLinksFromRawText(unpacked, embedUrl, label, subtitleCallback, callback)) {
                found = true
            }
        }

        val fireHash = Regex("""FirePlayer\s*\(\s*["']([a-f0-9]{16,})["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
            ?: Regex("""/player/index\.php\?data=([a-f0-9]{16,})""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)

        if (!fireHash.isNullOrBlank()) {
            val uri = URI(embedUrl)
            val origin = "${uri.scheme}://${uri.host}"
            val postUrl = "$origin/player/index.php?data=$fireHash&do=getVideo"
            try {
                val fireResp = app.post(
                    postUrl,
                    referer = embedUrl,
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf("hash" to fireHash, "r" to referer)
                ).text
                if (parseLinksFromRawText(fireResp, embedUrl, label, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        // DÜZELTME: ExtractorApi içinde MainAPI.fixUrlNull yerine bağımsız resolveUrlNull kullanıldı
        val nestedIframes = resp.document.select("iframe[src], iframe[data-src]")
            .mapNotNull { resolveUrlNull(embedUrl, it.attr("src").ifBlank { it.attr("data-src") }) }
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

    suspend fun parseLinksFromRawText(
        rawText: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val unescaped = rawText.replace("""\/""", "/")

        val subRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        for (subMatch in subRegex.findAll(unescaped)) {
            subtitleCallback(newSubtitleFile("Türkçe", subMatch.value))
        }

        val streamRegex = Regex(
            """https?://[^\s"'<>\\]+?(?:\.m3u8|\.mp4|master\.txt|playlist\.m3u8)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        for (m in streamRegex.findAll(unescaped)) {
            val streamUrl = m.value
            if (streamUrl.endsWith(".js", true) || streamUrl.endsWith(".ts", true)) continue
            emitDirectLink(name, streamUrl, referer, label, callback)
            found = true
        }

        val fieldRegex = Regex("""["'](?:file|videoSource|securedLink)["']\s*:\s*["'](https?://[^"']+)["']""")
        for (m in fieldRegex.findAll(unescaped)) {
            val link = m.groupValues[1]
            if (link.endsWith(".vtt", true) || link.endsWith(".srt", true)) {
                subtitleCallback(newSubtitleFile("Türkçe", link))
            } else if (!link.endsWith(".js", true) && !link.endsWith(".png", true)) {
                emitDirectLink(name, link, referer, label, callback)
                found = true
            }
        }

        return found
    }
}

/**
 * VidRockExtractor (https://vidrock.net/movie/... ve https://vidrock.net/tv/...)[span_25](start_span)[span_25](end_span)[span_26](start_span)[span_26](end_span)
 */
open class VidRockExtractor : ExtractorApi() {
    override val name = "VidRock"
    override val mainUrl = "https://vidrock.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractVidRock(url, referer ?: mainUrl, name, subtitleCallback, callback)
    }

    suspend fun extractVidRock(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val pageResp = app.get(url, referer = referer)
        val pageHtml = pageResp.text

        if (VideoParkExtractor().parseLinksFromRawText(pageHtml, url, label, subtitleCallback, callback)) {
            found = true
        }

        val tmdbMatch = Regex("""["']?(?:tmdb|tmdbId|id)["']?\s*[:=]\s*["']?(\d{3,})["']?""").find(pageHtml)?.groupValues?.get(1)
        val tvMatch = Regex("""/tv/([^/?#]+)/(\d+)/(\d+)""").find(url)
        val movieMatch = Regex("""/movie/([^/?#]+)""").find(url)

        val apiEndpoint = when {
            tvMatch != null -> {
                val id = tmdbMatch ?: tvMatch.groupValues[1]
                val s = tvMatch.groupValues[2]
                val e = tvMatch.groupValues[3]
                "$mainUrl/api/tv/$id/$s/$e"
            }
            movieMatch != null -> {
                val id = tmdbMatch ?: movieMatch.groupValues[1]
                "$mainUrl/api/movie/$id"
            }
            else -> null
        }

        if (apiEndpoint != null) {
            try {
                val apiText = app.get(
                    apiEndpoint,
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                if (VideoParkExtractor().parseLinksFromRawText(apiText, url, label, subtitleCallback, callback)) {
                    found = true
                }

                val urlFieldRegex = Regex("""["']url["']\s*:\s*["']([^"']{16,})["']""")
                for (m in urlFieldRegex.findAll(apiText)) {
                    val rawVal = m.groupValues[1]
                    if (rawVal.startsWith("http")) {
                        emitDirectLink(name, rawVal, url, label, callback)
                        found = true
                    } else {
                        val decoded = tryDecodeBase64Url(rawVal)
                        if (decoded != null && decoded.startsWith("http")) {
                            emitDirectLink(name, decoded, url, label, callback)
                            found = true
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return found
    }

    private fun tryDecodeBase64Url(encoded: String): String? {
        return try {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            String(bytes, Charsets.UTF_8).takeIf { it.contains("http") }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * MainAPI dışındaki sınıflarda (ExtractorApi vb.) güvenle kullanılabilen bağımsız URL çözümleyici.
 */
private fun resolveUrlNull(baseUrl: String, url: String?): String? {
    val clean = url?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("javascript:") && !it.startsWith("data:") }
        ?: return null
    return try {
        when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> URI(baseUrl).resolve(clean).toString()
        }
    } catch (e: Exception) {
        null
    }
}

private suspend fun emitDirectLink(
    sourceName: String,
    url: String,
    referer: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    val isM3u8 = url.contains(".m3u8", ignoreCase = true) || url.contains("master.txt", ignoreCase = true)
    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
    callback(
        newExtractorLink(
            source = sourceName,
            name = "$sourceName - $label",
            url = url,
            type = linkType
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
        }
    )
}
