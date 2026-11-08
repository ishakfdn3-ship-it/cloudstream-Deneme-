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
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
    override var name = "JetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // E09_js_visitor-tracking_2.js için 64 karakterlik hex visitor_key üretimi
    private val visitorKey: String by lazy {
        (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).take(64)
    }

    private fun getBaseHeaders(referer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "Referer" to referer,
            "Cookie" to "visitor_key=$visitorKey"
        )
    }

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
        val doc = app.get(url, headers = getBaseHeaders()).document

        val items = doc.select("div.film-card.jf-card, div.film-card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = doc.selectFirst(".pagination-modern .next-page a[href], .pagination-modern a[rel=next]") != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = selectFirst("h3.fc-title, .search-result-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val img = selectFirst(".film-poster img, img.card-img-top, img")
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

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        try {
            val jsonText = app.get(
                "$mainUrl/arama-json?q=$encoded",
                headers = getBaseHeaders() + mapOf(
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

        val doc = app.get("$mainUrl/arama?q=$encoded", headers = getBaseHeaders()).document
        return doc.select("div.film-card.jf-card, div.film-card").mapNotNull { it.toSearchResult() }
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
        val doc = app.get(url, headers = getBaseHeaders()).document

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

        val tmdbId = Regex("""themoviedb\.org/(?:tv|movie)/(\d+)""").find(doc.html())?.groupValues?.get(1)

        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.trim()
            ?: doc.selectFirst("[data-film-id]")?.attr("data-film-id")?.trim().orEmpty()

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
        val recommendations = doc.select(".similar-film-item div.film-card").mapNotNull { it.toSearchResult() }

        val episodeButtons = doc.select(".episode-btn, button[data-episode], .player-source-btn[data-episode]")
        val sectionMeta = doc.selectFirst("meta[property=article:section]")?.attr("content").orEmpty()
        val isSeries = url.contains("/dizi/") || episodeButtons.isNotEmpty() || sectionMeta.equals("Diziler", ignoreCase = true)

        if (isSeries) {
            val groupedEpisodes = LinkedHashMap<Pair<Int, Int>, MutableList<SourceVariant>>()

            for (btn in episodeButtons) {
                val s = btn.attr("data-season").toIntOrNull()
                    ?: btn.closest(".episode-list")?.attr("data-season")?.toIntOrNull()
                    ?: 1
                val e = btn.attr("data-episode").toIntOrNull()
                    ?: Regex("""[Bb]ölüm\s*(\d+)|[Ee](\d+)|(\d+)""").find(btn.text())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                    ?: continue

                val srcIdx = btn.attr("data-source-index").ifBlank { "0" }
                val pType = btn.attr("data-player-type")
                    .ifBlank { btn.closest(".player-sources-group")?.attr("data-player-group").orEmpty() }
                    .ifBlank { "genel" }

                val key = s to e
                val list = groupedEpisodes.getOrPut(key) { mutableListOf() }
                val label = playerTypeDisplayName(pType, btn.text().trim())
                if (list.none { it.sourceIndex == srcIdx && it.playerType == pType }) {
                    list.add(SourceVariant(srcIdx, pType, label))
                }
            }

            if (groupedEpisodes.isEmpty()) {
                val availableTypes = doc.select(".player-type-btn")
                    .mapNotNull { it.attr("data-player-type").takeIf { t -> t.isNotBlank() } }
                    .ifEmpty { listOf("dublaj", "altyazili", "genel") }

                var globalIndex = 0
                for (scriptEl in doc.select("script[type=application/ld+json]")) {
                    val rawJson = scriptEl.data()
                    if (!rawJson.contains("TVSeries")) continue
                    val ld = tryParseJson<JsonLdSeries>(rawJson) ?: continue
                    ld.containsSeason?.forEach { seasonObj ->
                        val sNum = seasonObj.seasonNumber ?: 1
                        seasonObj.episodes?.forEachIndexed { idx, epObj ->
                            val eNum = epObj.episodeNumber ?: (idx + 1)
                            val key = sNum to eNum
                            val list = groupedEpisodes.getOrPut(key) { mutableListOf() }
                            for (pType in availableTypes) {
                                list.add(SourceVariant(globalIndex.toString(), pType, playerTypeDisplayName(pType, "Bölüm $eNum")))
                            }
                            globalIndex++
                        }
                    }
                }
            }

            // Eğer hala boşsa en azından 1. Sezon 1. Bölümü oluştur ki dizi sayfası boş kalmasın
            if (groupedEpisodes.isEmpty()) {
                groupedEpisodes[1 to 1] = mutableListOf(
                    SourceVariant("0", "dublaj", "Türkçe Dublaj"),
                    SourceVariant("0", "altyazili", "Türkçe Altyazılı"),
                    SourceVariant("0", "genel", "Orijinal / Dil+")
                )
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
                val pType = btn.attr("data-player-type")
                    .ifBlank { btn.closest(".player-sources-group")?.attr("data-player-group").orEmpty() }
                    .ifBlank { "genel" }
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
        } ?: return false

        val variantsToTry = linkData.variants.ifEmpty {
            listOf(
                SourceVariant("0", "genel", "Orijinal / Dil+"),
                SourceVariant("0", "dublaj", "Türkçe Dublaj"),
                SourceVariant("0", "altyazili", "Türkçe Altyazılı")
            )
        }

        // VideoPark URL'lerinin aynı loadLinks çağrısında mükerrer taranmasını önleyen küme
        val processedEmbedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // 1. ADIM: Tüm varyantlara PARALEL olarak (argamap) POST /jetplayer isteği at (Hız 10x artar)
        variantsToTry.argamap { variant ->
            if (linkData.filmId.isNotBlank()) {
                try {
                    // E09_js_film-page.min_2.js'deki birebir POST gövdesi
                    val postMap = mapOf(
                        "film_id" to linkData.filmId,
                        "source_index" to variant.sourceIndex,
                        "player_type" to variant.playerType
                    )

                    val responseText = app.post(
                        "$mainUrl/jetplayer",
                        referer = linkData.pageUrl,
                        headers = getBaseHeaders(linkData.pageUrl) + mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl
                        ),
                        data = postMap
                    ).text

                    handlePlayerHtmlResponse(
                        html = responseText,
                        referer = linkData.pageUrl,
                        variant = variant,
                        linkData = linkData,
                        processedEmbedUrls = processedEmbedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        // 2. ADIM: Eğer 1. Adımda hiçbir VideoPark/JetEmbed iframe'i gelmediyse (veya ban mesajı döndüyse) doğrudan IMDb ID ile çöz
        if (!linkData.imdbId.isNullOrBlank() && processedEmbedUrls.none { it.contains("videopark") || it.contains("jetembed") }) {
            val directJetEmbedUrl = if (linkData.isSeries) {
                "https://videopark.top/jetembed/${linkData.imdbId}?type=tv&season=${linkData.season ?: 1}&episode=${linkData.episode ?: 1}"
            } else {
                "https://videopark.top/jetembed/${linkData.imdbId}"
            }
            try {
                VideoParkExtractor().extractWithContext(
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return true
    }

    private suspend fun handlePlayerHtmlResponse(
        html: String,
        referer: String,
        variant: SourceVariant,
        linkData: LoadLinkData,
        processedEmbedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val frag: Document = Jsoup.parseBodyFragment(html, mainUrl)

        for (srcEl in frag.select("video[src], video source[src]")) {
            val videoUrl = fixUrlNull(srcEl.attr("src")) ?: continue
            emitDirectLink(name, videoUrl, referer, variant.label, callback)
        }

        val iframeUrls = frag.select("iframe[src], iframe[data-src]")
            .filterNot { it.id() == "yt-frame" || it.id() == "trailerIframe" }
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { it.contains("youtube.com/embed/") || it.contains("youtu.be/") }
            .distinct()

        iframeUrls.argamap { iframeUrl ->
            if (!processedEmbedUrls.add(iframeUrl)) return@argamap

            try {
                when {
                    iframeUrl.contains("/jetembed/") || iframeUrl.contains("videopark.") -> {
                        VideoParkExtractor().extractWithContext(
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
                    }
                    iframeUrl.contains("embedmaster") || iframeUrl.contains("embdmstrplayer") -> {
                        EmbedMasterExtractor().extractEmbedMaster(iframeUrl, referer, variant.label, subtitleCallback, callback)
                    }
                    else -> {
                        if (!loadExtractor(iframeUrl, referer, subtitleCallback, callback)) {
                            VideoParkExtractor().scrapeGenericEmbedPage(iframeUrl, referer, variant.label, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
    }
}

/**
 * VideoParkExtractor - Paralel (argamap) ve Dizi/Film Tam Uyumlu
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
            isSeries = url.contains("type=tv") || url.contains("type=series") || url.contains("season="),
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
        // Eğer imdbOverride zaten biliniyorsa gereksiz yere embed HTML'ini indirme, doğrudan API'ye git!
        var imdbId = imdbOverride ?: Regex("""(tt\d+)""").find(url)?.groupValues?.get(1)
        var initType: String? = null
        var initSeason: String? = null
        var initEpisode: String? = null

        if (imdbId == null) {
            val embedHtml = try {
                app.get(url, referer = referer).text
            } catch (e: Exception) {
                ""
            }
            val initRegex = Regex("""player\.init\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*([^,)]+)\s*,\s*([^)]+)\)""")
            val match = initRegex.find(embedHtml)
            imdbId = match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            initType = match?.groupValues?.get(2)?.trim()
            initSeason = match?.groupValues?.get(3)?.trim()?.trim('\'', '"')?.takeIf { it != "null" && it.isNotBlank() }
            initEpisode = match?.groupValues?.get(4)?.trim()?.trim('\'', '"')?.takeIf { it != "null" && it.isNotBlank() }
        }

        if (imdbId.isNullOrBlank()) return false

        val mediaType = when {
            isSeries -> "tv"
            initType != null && (initType.equals("tv", true) || initType.equals("series", true)) -> "tv"
            else -> "movie"
        }

        val seasonArg = seasonOverride?.toString() ?: initSeason ?: if (mediaType == "tv") "1" else null
        val episodeArg = episodeOverride?.toString() ?: initEpisode ?: if (mediaType == "tv") "1" else null

        val uri = URI(url)
        val origin = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else mainUrl
        val dilParam = Regex("""[?&]dil=([^&#]+)""").find(url)?.groupValues?.get(1)

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
        val allSources = parsed?.sources?.sortedByDescending { it.priorityScore ?: 0 }.orEmpty()

        // HIZ OPTİMİZASYONU: Yalnızca hızlı ve gerçek video döndüren kaynakları filtrele veya ilk 5 kaynağı paralel çalıştır
        val priorityNames = listOf("embedmaster", "vidrock", "vidfast", "peachify", "vidlink", "videasy", "moviesapi")
        val filteredSources = allSources.filter { src ->
            val n = src.name?.lowercase().orEmpty()
            val u = src.url?.lowercase().orEmpty()
            priorityNames.any { n.contains(it) || u.contains(it) }
        }.ifEmpty { allSources.take(5) }

        // PARALEL ÇALIŞTIRMA: Sırayla beklemek yerine tüm kaynakları aynı anda çöz
        filteredSources.argamap { src ->
            val rawTargetUrl = src.url?.trim()?.takeIf { it.isNotBlank() } ?: return@argamap
            val targetUrl = if (mediaType == "tv" && seasonArg != null && episodeArg != null) {
                adaptEmbedUrlForSeries(rawTargetUrl, imdbId, tmdbOverride, seasonArg, episodeArg)
            } else {
                rawTargetUrl
            }

            val sourceLabel = "$labelPrefix [${src.name ?: "Kaynak"}]"
            try {
                when {
                    targetUrl.contains("embedmaster") || targetUrl.contains("embdmstrplayer") -> {
                        EmbedMasterExtractor().extractEmbedMaster(targetUrl, url, sourceLabel, subtitleCallback, callback)
                    }
                    targetUrl.contains("vidrock.net") -> {
                        VidRockExtractor().extractVidRock(targetUrl, url, sourceLabel, subtitleCallback, callback)
                    }
                    else -> {
                        if (!loadExtractor(targetUrl, url, subtitleCallback, callback)) {
                            scrapeGenericEmbedPage(targetUrl, url, sourceLabel, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return true
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
            rawUrl.contains("embedmaster.link/movie/") -> rawUrl.replace("/movie/$imdbId", "/tv/$imdbId/$season/$episode")
            rawUrl.contains("embedmaster.link/embed/movie/") -> rawUrl.replace("/embed/movie/$imdbId", "/embed/tv/$imdbId/$season/$episode")
            rawUrl.contains("vidrock.net/movie/") -> "https://vidrock.net/tv/$imdbId/$season/$episode"
            rawUrl.contains("vidlink.pro/movie/") -> "https://vidlink.pro/tv/$idToUse/$season/$episode"
            rawUrl.contains("vidfast.pro/movie/") -> "https://vidfast.pro/tv/$imdbId/$season/$episode"
            rawUrl.contains("moviesapi.to/movie/") -> "https://moviesapi.to/tv/$imdbId-$season-$episode"
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
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        if (depth > 2) return false
        val resp = app.get(embedUrl, referer = referer)
        val body = resp.text
        var found = parseLinksFromRawText(body, embedUrl, label, subtitleCallback, callback)

        val unpacked = getAndUnpack(body)
        if (unpacked.isNotBlank() && unpacked != body) {
            if (parseLinksFromRawText(unpacked, embedUrl, label, subtitleCallback, callback)) {
                found = true
            }
        }

        val nestedIframes = resp.document.select("iframe[src], iframe[data-src]")
            .mapNotNull { resolveUrlNull(embedUrl, it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { it.contains("youtube.com") || it == embedUrl }
            .distinct()

        for (nested in nestedIframes) {
            try {
                if (nested.contains("embedmaster") || nested.contains("embdmstrplayer")) {
                    if (EmbedMasterExtractor().extractEmbedMaster(nested, embedUrl, label, subtitleCallback, callback)) {
                        found = true
                    }
                } else if (loadExtractor(nested, embedUrl, subtitleCallback, callback)) {
                    found = true
                } else if (scrapeGenericEmbedPage(nested, embedUrl, label, subtitleCallback, callback, depth + 1)) {
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

        val fieldRegex = Regex("""["'](?:file|videoSource|securedLink|source)["']\s*:\s*["'](https?://[^"']+)["']""")
        for (m in fieldRegex.findAll(unescaped)) {
            val link = m.groupValues[1]
            if (link.endsWith(".vtt", true) || link.endsWith(".srt", true)) {
                subtitleCallback(newSubtitleFile("Türkçe", link))
            } else if (!link.endsWith(".js", true) && !link.endsWith(".png", true) && !link.endsWith(".jpg", true)) {
                emitDirectLink(name, link, referer, label, callback)
                found = true
            }
        }

        return found
    }
}

/**
 * EmbedMasterExtractor (embedmaster.link & embdmstrplayer.com)
 * JetFilmizle'deki Türkçe Dublaj ve Altyazılı dizi/filmlerin ana oynatıcısı!
 */
class EmbdMstrPlayerExtractor : EmbedMasterExtractor() {
    override val name = "EmbdMstrPlayer"
    override val mainUrl = "https://embdmstrplayer.com"
}

open class EmbedMasterExtractor : ExtractorApi() {
    override val name = "EmbedMaster"
    override val mainUrl = "https://embedmaster.link"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractEmbedMaster(url, referer ?: "https://videopark.top/", name, subtitleCallback, callback)
    }

    suspend fun extractEmbedMaster(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        var currentUrl = url
        var resp = app.get(currentUrl, referer = referer)
        var body = resp.text

        // 1. Katman: embedmaster.link içinden embdmstrplayer.com iframe'ine geçiş
        val innerIframe = resp.document.selectFirst("iframe[src], iframe[data-src]")?.let {
            resolveUrlNull(currentUrl, it.attr("src").ifBlank { it.attr("data-src") })
        }
        if (!innerIframe.isNullOrBlank() && innerIframe != currentUrl) {
            val prevUrl = currentUrl
            currentUrl = innerIframe
            resp = app.get(currentUrl, referer = prevUrl)
            body = resp.text
        }

        val uri = URI(currentUrl)
        val origin = "${uri.scheme}://${uri.host}"

        // 2. Doğrudan veya Unpack edilmiş metinden linkleri ayıkla
        if (VideoParkExtractor().parseLinksFromRawText(body, "$origin/", label, subtitleCallback, callback)) {
            found = true
        }
        val unpacked = getAndUnpack(body)
        if (unpacked.isNotBlank() && unpacked != body) {
            if (VideoParkExtractor().parseLinksFromRawText(unpacked, "$origin/", label, subtitleCallback, callback)) {
                found = true
            }
        }

        // 3. FirePlayer / getVideo API kontrolü (/player/index.php?data=...&do=getVideo)
        val fireHash = Regex("""FirePlayer\s*\(\s*["']([a-fA-F0-9]{16,64})["']""").find(body)?.groupValues?.get(1)
            ?: Regex("""/player/index\.php\?data=([a-fA-F0-9]{16,64})""").find(body)?.groupValues?.get(1)
            ?: Regex("""/video/([a-fA-F0-9]{16,64})""").find(currentUrl)?.groupValues?.get(1)

        if (!fireHash.isNullOrBlank()) {
            try {
                val postUrl = "$origin/player/index.php?data=$fireHash&do=getVideo"
                val fireResp = app.post(
                    postUrl,
                    referer = currentUrl,
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to origin
                    ),
                    data = mapOf("hash" to fireHash, "r" to referer)
                ).text

                if (VideoParkExtractor().parseLinksFromRawText(fireResp, "$origin/", label, subtitleCallback, callback)) {
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
    val isM3u8 = url.contains(".m3u8", ignoreCase = true) || url.contains("master.txt", ignoreCase = true) || url.contains(".txt", ignoreCase = true)
    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
    val uri = try { URI(referer) } catch (e: Exception) { null }
    val origin = if (uri?.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else referer.trimEnd('/')

    callback(
        newExtractorLink(
            source = sourceName,
            name = "$sourceName - $label",
            url = url,
            type = linkType
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Origin" to origin,
                "Referer" to referer
            )
        }
    )
}
