package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.app"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler"                  to "Son Filmler",
        "${mainUrl}/turkce-dublaj"            to "Türkçe Dublaj Filmler",
        "${mainUrl}/turkce-altyazili"         to "Türkçe Altyazılı Filmler",
        "${mainUrl}/diziler"                  to "Yabancı ve Yerli Diziler",
        "${mainUrl}/filmler/en-cok-izlenenler" to "En Çok İzlenenler",
        "${mainUrl}/imdb-top"                 to "IMDb Top 250"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/sayfa-$page"
        val document = app.get(url).document
        val home = document.select("div.film-card, article.movie").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val href   = fixUrlNull(anchor.attr("href")) ?: return null
        val title  = this.selectFirst("h3.fc-title, .search-result-title, h2, h3")?.text()?.trim()
            ?: anchor.attr("title").replace("Full Türkçe Dublaj İzle", "").replace("Full HD İzle", "").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }
        )

        val isTv = href.contains("/dizi/")
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Önce hızlı JSON API'sini dene (arama-json?q=)
        try {
            val jsonResponse = app.get(
                "${mainUrl}/arama-json?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "${mainUrl}/"
                )
            ).parsedSafe<JetSearchJson>()

            jsonResponse?.results?.forEach { item ->
                val itemUrl = fixUrlNull(item.url) ?: return@forEach
                val poster  = fixUrlNull(item.poster)
                if (item.type == "dizi" || itemUrl.contains("/dizi/")) {
                    results.add(newTvSeriesSearchResponse(item.title ?: "", itemUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = item.year?.toIntOrNull()
                    })
                } else {
                    results.add(newMovieSearchResponse(item.title ?: "", itemUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = item.year?.toIntOrNull()
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("JetFilmizle", "JSON Arama hatası: ${e.message}")
        }

        if (results.isNotEmpty()) return results

        // 2. JSON boş dönerse standart HTML aramasına (/arama?q=) düş
        val document = app.get("${mainUrl}/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}").document
        return document.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.film-title, meta[name=film:title]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img.film-poster")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val year = document.selectFirst("meta[name=film:year]")?.attr("content")?.toIntOrNull()
            ?: document.selectFirst("span.jf-detay-kutu__deger a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()

        val description = document.selectFirst("div.film-description div.description-text")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val rating = document.selectFirst("meta[name=film:rating]")?.attr("content")?.toRatingInt()
            ?: document.selectFirst("a.imdb-badge b")?.text()?.trim()?.toRatingInt()

        val tags = document.select("div.categories-container-details a.category-badge-details").map { it.text().trim() }
        val actors = document.select("div.actors-grid div.cast-card div.cast-info a").map { it.text().trim() }
        val trailer = document.selectFirst("iframe#trailerIframe")?.attr("data-src")

        val filmId = document.selectFirst("input[name=film_id]")?.attr("value")
            ?: document.selectFirst("[data-film-id]")?.attr("data-film-id") ?: ""

        val imdbId = document.selectFirst("meta[name=film:imdb_id]")?.attr("content")
            ?: document.selectFirst("a[href*='imdb.com/title/tt']")?.attr("href")
                ?.substringAfter("title/")?.substringBefore("/") ?: ""

        // Dizi mi yoksa Film mi kontrolü (.episode-btn varlığı veya URL yapısı)
        val episodeButtons = document.select(".episode-btn.player-source-btn, button.episode-btn")
        val isTvSeries = url.contains("/dizi/") || episodeButtons.isNotEmpty()

        if (isTvSeries) {
            val episodeMap = mutableMapOf<Pair<Int, Int>, MutableList<SourceParam>>()

            episodeButtons.forEach { btn ->
                val s = btn.attr("data-season").toIntOrNull() ?: 1
                val e = btn.attr("data-episode").toIntOrNull() ?: 1
                val sIdx = btn.attr("data-source-index").ifEmpty { "0" }
                val pType = btn.attr("data-player-type").ifEmpty { "genel" }
                val label = btn.text().trim().ifEmpty { "Bölüm $e" }

                val key = Pair(s, e)
                episodeMap.getOrPut(key) { mutableListOf() }.add(
                    SourceParam(
                        filmId = filmId,
                        imdbId = imdbId,
                        sourceIndex = sIdx,
                        playerType = pType,
                        label = label,
                        season = s,
                        episode = e
                    )
                )
            }

            val episodes = episodeMap.map { (seasonEp, params) ->
                val dataJson = AppUtils.toJson(LoadData(url, filmId, imdbId, isTv = true, sources = params))
                newEpisode(dataJson) {
                    this.name = "${seasonEp.first}. Sezon ${seasonEp.second}. Bölüm"
                    this.season = seasonEp.first
                    this.episode = seasonEp.second
                }
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            // Film için tüm kaynak butonlarını (.player-source-btn) topla
            val sourceButtons = document.select("button.player-source-btn:not(.episode-btn)")
            val sourcesList = mutableListOf<SourceParam>()

            sourceButtons.forEach { btn ->
                val sIdx = btn.attr("data-source-index").ifEmpty { "0" }
                val pType = btn.attr("data-player-type").ifEmpty { "genel" }
                val label = btn.text().trim().ifEmpty { "JetPlayer" }
                sourcesList.add(SourceParam(filmId, imdbId, sIdx, pType, label, null, null))
            }

            // Eğer hiç buton bulunamazsa varsayılan olarak genel/dublaj/altyazılı ekle
            if (sourcesList.isEmpty() && filmId.isNotEmpty()) {
                sourcesList.add(SourceParam(filmId, imdbId, "0", "genel", "Orijinal / Dil+", null, null))
                sourcesList.add(SourceParam(filmId, imdbId, "0", "dublaj", "Türkçe Dublaj", null, null))
                sourcesList.add(SourceParam(filmId, imdbId, "0", "altyazili", "Türkçe Altyazılı", null, null))
            }

            val dataJson = AppUtils.toJson(LoadData(url, filmId, imdbId, isTv = false, sources = sourcesList))

            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = AppUtils.tryParseJson<LoadData>(data) ?: return false

        val typeLabels = mapOf(
            "dublaj" to "TR Dublaj",
            "altyazili" to "TR Altyazılı",
            "genel" to "Orijinal/Dil+"
        )

        // 1. AŞAMA: Sitenin kendi `jetplayer` POST uç noktasına her bir kaynak için istek at
        for (src in loadData.sources) {
            try {
                val postResponse = app.post(
                    "${mainUrl}/jetplayer",
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to loadData.pageUrl,
                        "Origin" to mainUrl
                    ),
                    data = mapOf(
                        "film_id" to src.filmId,
                        "source_index" to src.sourceIndex,
                        "player_type" to src.playerType
                    )
                ).text

                val iframeDoc = Jsoup.parse(postResponse)
                val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src")?.let { fixUrlNull(it) }

                val prefix = "${typeLabels[src.playerType] ?: src.playerType} - ${src.label}"

                if (!iframeSrc.isNullOrEmpty()) {
                    extractFromUrl(iframeSrc, loadData.pageUrl, prefix, src.season, src.episode, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("JetFilmizle", "jetplayer POST hatası: ${e.message}")
            }
        }

        // 2. AŞAMA: Eğer IMDb ID mevcutsa doğrudan VideoPark API'sini de (Yedek/Garanti olarak) sorgula
        if (loadData.imdbId.isNotEmpty()) {
            val firstSrc = loadData.sources.firstOrNull()
            extractVideoParkApi(
                imdbId = loadData.imdbId,
                type = if (loadData.isTv) "series" else "movie",
                season = firstSrc?.season,
                episode = firstSrc?.episode,
                prefix = "JetGlobal",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        return true
    }

    private suspend fun extractFromUrl(
        iframeUrl: String,
        referer: String,
        prefix: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // VideoPark / JetEmbed yapısı (Örn: https://videopark.top/jetembed/tt32741341?dil=dublaj)
            iframeUrl.contains("videopark.top") || iframeUrl.contains("/jetembed/") -> {
                val imdbRegex = Regex("""(tt\d+)""")
                val imdbId = imdbRegex.find(iframeUrl)?.groupValues?.get(1) ?: ""
                if (imdbId.isNotEmpty()) {
                    val isSeries = season != null && episode != null
                    extractVideoParkApi(
                        imdbId = imdbId,
                        type = if (isSeries) "series" else "movie",
                        season = season,
                        episode = episode,
                        prefix = prefix,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                } else {
                    // Sayfa içinden player.init('tt...', 'movie', s, e) yakala
                    val html = app.get(iframeUrl, referer = referer).text
                    val initMatch = Regex("""player\.init\(\s*['"](tt\d+)['"]\s*,\s*['"](\w+)['"]""").find(html)
                    val parsedImdb = initMatch?.groupValues?.get(1) ?: ""
                    val parsedType = initMatch?.groupValues?.get(2) ?: "movie"
                    if (parsedImdb.isNotEmpty()) {
                        extractVideoParkApi(parsedImdb, parsedType, season, episode, prefix, subtitleCallback, callback)
                    }
                }
            }

            // EmbedMaster / Embdmstrplayer yapısı
            iframeUrl.contains("embedmaster") || iframeUrl.contains("embdmstrplayer") -> {
                extractEmbedMaster(iframeUrl, referer, prefix, subtitleCallback, callback)
            }

            // Diğer tüm bilindik CloudStream extractor'ları ve doğrudan JWPlayer m3u8 araması
            else -> {
                val handled = loadExtractor(iframeUrl, referer, subtitleCallback) { link ->
                    callback(link)
                }
                if (!handled) {
                    extractGenericPlayer(iframeUrl, referer, prefix, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun extractVideoParkApi(
        imdbId: String,
        type: String,
        season: Int?,
        episode: Int?,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val queryParams = buildString {
                append("?imdb=$imdbId")
                if (type == "series" && season != null && episode != null) {
                    append("&type=series&season=$season&episode=$episode")
                } else {
                    append("&type=movie")
                }
            }
            val apiUrl = "https://videopark.top/jetembed/backend/api_sources.php$queryParams"
            val refererUrl = "https://videopark.top/jetembed/$imdbId"

            val responseText = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to refererUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).text

            val apiData = AppUtils.tryParseJson<VideoParkResponse>(responseText) ?: return

            apiData.sources?.forEach { source ->
                val srcUrl = source.url ?: return@forEach
                val srcName = "$prefix [${source.Title ?: source.name ?: "Kaynak"}]"

                when {
                    srcUrl.contains("embedmaster") || srcUrl.contains("embdmstrplayer") -> {
                        extractEmbedMaster(srcUrl, refererUrl, srcName, subtitleCallback, callback)
                    }
                    else -> {
                        val handled = loadExtractor(srcUrl, refererUrl, subtitleCallback, callback)
                        if (!handled) {
                            extractGenericPlayer(srcUrl, refererUrl, srcName, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("JetFilmizle", "VideoPark API hatası: ${e.message}")
        }
    }

    private suspend fun extractEmbedMaster(
        url: String,
        referer: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Önce embedmaster.link sayfasını çek, içinde embdmstrplayer.com iframe'i varsa oraya geç
            val firstResp = app.get(url, referer = referer)
            var pageHtml = firstResp.text
            var baseHost = getBaseUrl(firstResp.url)

            val innerIframe = firstResp.document.selectFirst("iframe[src]")?.attr("src")
            if (!innerIframe.isNullOrEmpty() && (innerIframe.contains("embdmstr") || innerIframe.contains("player"))) {
                val targetUrl = fixUrl(innerIframe, baseHost)
                val secondResp = app.get(targetUrl, referer = url)
                pageHtml = secondResp.text
                baseHost = getBaseUrl(secondResp.url)
            }

            // 2. Sayfa içindeki base64 veya doğrudan gömülü m3u8 / master.txt / .dat yapılarını kontrol et
            extractGenericPlayerFromHtml(pageHtml, "$baseHost/", prefix, subtitleCallback, callback)

            // 3. FirePlayer / EmbedMaster post token mekanizması varsa (ör. /player/index.php?data=...&do=getVideo)
            val hashMatch = Regex("""(?:video|id|hash)['"]?\s*[:=]\s*['"]([a-fA-F0-9]{16,64})['"]""").find(pageHtml)
            val videoHash = hashMatch?.groupValues?.get(1)
            if (!videoHash.isNullOrEmpty()) {
                val postUrl = "$baseHost/player/index.php?data=$videoHash&do=getVideo"
                val videoJson = app.post(
                    postUrl,
                    headers = mapOf(
                        "Referer" to "$baseHost/",
                        "Origin" to baseHost,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf("hash" to videoHash, "r" to referer)
                ).parsedSafe<FirePlayerResponse>()

                val streamUrl = videoJson?.securedLink ?: videoJson?.videoSource
                if (!streamUrl.isNullOrEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            source  = "$prefix (EmbedMaster)",
                            name    = "$prefix (EmbedMaster)",
                            url     = streamUrl,
                            referer = "$baseHost/",
                            quality = Qualities.Unknown.value,
                            isM3u8  = true,
                            headers = mapOf("Origin" to baseHost, "Referer" to "$baseHost/")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("JetFilmizle", "EmbedMaster çözme hatası: ${e.message}")
        }
    }

    private suspend fun extractGenericPlayer(
        url: String,
        referer: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, referer = referer)
            extractGenericPlayerFromHtml(resp.text, url, prefix, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("JetFilmizle", "Generic player hatası: ${e.message}")
        }
    }

    private fun extractGenericPlayerFromHtml(
        html: String,
        playerUrl: String,
        prefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseHost = getBaseUrl(playerUrl)

        // M3U8 veya master.txt bağlantılarını yakala
        val m3u8Regex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|txt|mp4)(?:\?[^"'\s]*)?)["']""")
        m3u8Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { streamUrl ->
            if (!streamUrl.contains(".vtt") && !streamUrl.contains(".srt")) {
                callback.invoke(
                    ExtractorLink(
                        source  = prefix,
                        name    = prefix,
                        url     = streamUrl.replace("\\/", "/"),
                        referer = "$baseHost/",
                        quality = Qualities.Unknown.value,
                        isM3u8  = !streamUrl.contains(".mp4"),
                        headers = mapOf("Origin" to baseHost, "Referer" to "$baseHost/")
                    )
                )
            }
        }

        // VTT veya SRT altyazılarını yakala
        val subRegex = Regex("""["'](https?://[^"'\s]+\.(?:vtt|srt)(?:\?[^"'\s]*)?)["']""")
        subRegex.findAll(html).map { it.groupValues[1] }.distinct().forEach { subUrl ->
            val cleanSub = subUrl.replace("\\/", "/")
            val lang = if (cleanSub.contains("tr", ignoreCase = true) || cleanSub.contains("tur", ignoreCase = true)) "Türkçe" else "Altyazı"
            subtitleCallback.invoke(SubtitleFile(lang, cleanSub))
        }
    }

    private fun getBaseUrl(url: String): String {
        val uri = java.net.URI(url)
        return "${uri.scheme}://${uri.host}"
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$baseUrl/${url.trimStart('/')}"
    }

    // --- Veri Modelleri (Data Classes) ---

    data class JetSearchJson(
        @JsonProperty("results") val results: List<JetSearchItem>? = null
    )

    data class JetSearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("year") val year: String? = null
    )

    data class SourceParam(
        @JsonProperty("filmId") val filmId: String,
        @JsonProperty("imdbId") val imdbId: String,
        @JsonProperty("sourceIndex") val sourceIndex: String,
        @JsonProperty("playerType") val playerType: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class LoadData(
        @JsonProperty("pageUrl") val pageUrl: String,
        @JsonProperty("filmId") val filmId: String,
        @JsonProperty("imdbId") val imdbId: String,
        @JsonProperty("isTv") val isTv: Boolean,
        @JsonProperty("sources") val sources: List<SourceParam>
    )

    data class VideoParkResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("sources") val sources: List<VideoParkSource>? = null
    )

    data class VideoParkSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val Title: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class FirePlayerResponse(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
