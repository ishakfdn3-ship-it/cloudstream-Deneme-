package com.filmmodu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Filmmodu : MainAPI() {

    override var mainUrl = "https://filmmodu.cc"
    override var name = "Filmmodu"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "/" to "Filmler",
        "/populer-filmler" to "Popüler Filmler",
        "/yabanci-dizi-izle" to "Yabancı Diziler",
    )

    companion object {
        private const val CARD_SELECTOR =
            "article.movie_box a.image, a.search_s, a.yd-afis"

        private const val EPISODE_SELECTOR =
            "ol.dz-liste[data-dz-liste] a.dz-bolum"

        private val EPISODE_PATH =
            Regex("/sezon-(\\d+)/bolum-(\\d+)(?:[/?#]|$)")

        // Tahmini genel player kalıbı: Vidmixi için doğrulanmadı.
        private val MEDIA_URL = Regex(
            """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        )

    /**
     * Göreli URL'leri ilgili sayfaya göre çözer.
     * Boş veya HTTP dışı değerleri kabul etmez.
     */
    private fun absoluteUrl(base: String, value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        return try {
            val resolved = URI(base).resolve(raw).toString()
            resolved.takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun imageUrl(element: Element): String? {
        val image = element.selectFirst("img") ?: return null

        return listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "src",
        ).asSequence()
            .map { image.attr(it).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("data:") }
            .mapNotNull { absoluteUrl(element.baseUri(), it) }
            .firstOrNull()
    }

    /**
     * İncelenen liste ve arama kartları için ortak dönüşüm.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a") {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val url = absoluteUrl(link.baseUri(), link.attr("href"))
            ?: return null

        val title = link.selectFirst(
            ".kart-ad, .detail .title, .yd-afis-ad"
        )?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link.attr("title")
                .trim()
                .removeSuffix(" izle")
                .takeIf { it.isNotEmpty() }
            ?: return null

        val poster = imageUrl(link)

        return when {
            url.contains("/dizi/") -> {
                // Bölüm kartı gelirse dizi ana sayfasını kullan.
                val seriesUrl = url.substringBefore("/sezon-")

                newTvSeriesSearchResponse(
                    title,
                    seriesUrl,
                    TvType.TvSeries,
                ) {
                    posterUrl = poster
                }
            }

            url.contains("/film/") -> {
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie,
                ) {
                    posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun Document.cards(): List<SearchResponse> =
        select(CARD_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        // Sayfalama endpoint'i henüz doğrulanmadığı için
        // aynı ilk sayfayı tekrar göstermek yerine devamı kapatılır.
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList()),
                hasNext = false,
            )
        }

        return try {
            val url = absoluteUrl("$mainUrl/", request.data)
                ?: return null

            val document = app.get(
                url,
                headers = requestHeaders,
            ).document

            newHomePageResponse(
                HomePageList(request.name, document.cards()),
                hasNext = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    override suspend fun quickSearch(
        query: String,
    ): List<SearchResponse>? = search(query)

    override suspend fun search(
        query: String,
    ): List<SearchResponse>? {
        val term = query.trim().takeIf { it.isNotEmpty() }
            ?: return emptyList()

        return try {
            val encoded = URLEncoder.encode(term, "UTF-8")

            /*
             * /arama/oneri HTML'de görüldü.
             * q parametresi ve XHR başlığı tahmindir.
             * Sunucu JSON döndürürse aşağıdaki HTML çözümü
             * sonuç üretmeyebilir.
             */
            val response = app.get(
                "$mainUrl/arama/oneri?q=$encoded",
                headers = requestHeaders + mapOf(
                    "Accept" to "text/html, application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                referer = "$mainUrl/",
            )

            Jsoup.parse(response.text, mainUrl).cards()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
    }

    /**
     * Sayfadaki Movie veya TVSeries JSON-LD nesnesini bulur.
     */
    private fun Document.contentJson(): JSONObject? {
        for (script in select("script[type=application/ld+json]")) {
            val text = script.data().trim()
            if (text.isEmpty()) continue

            try {
                val json = JSONObject(text)
                val type = json.optString("@type")

                if (type == "Movie" || type == "TVSeries") {
                    return json
                }
            } catch (error: Exception) {
                // JSON-LD bozuksa HTML yedekleri kullanılacak.
            }
        }

        return null
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key)
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()

        return (0 until array.length())
            .mapNotNull { index ->
                array.optString(index)
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "null" }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(
                url,
                headers = requestHeaders,
            )
            val document = response.document
            val json = document.contentJson()

            val title = json?.stringOrNull("name")
                ?: document.selectFirst("h1")?.text()
                    ?.trim()
                    ?.removeSuffix(" izle")
                    ?.takeIf { it.isNotEmpty() }
                ?: return null

            val posterRaw = json?.stringOrNull("image")
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")

            val poster = absoluteUrl(url, posterRaw)

            val description = json?.stringOrNull("description")
                ?: document.selectFirst(".fl-ozet")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            val yearText = json?.stringOrNull("dateCreated")
                ?: json?.stringOrNull("startDate")

            val releaseYear = yearText
                ?.take(4)
                ?.toIntOrNull()

            val genres = json?.stringList("genre")
                .orEmpty()
                .ifEmpty {
                    document.select("a[rel*=category]")
                        .map { it.text().trim() }
                        .filter { it.isNotEmpty() }
                }

            val type = json?.stringOrNull("@type")
            val ogType = document
                .selectFirst("meta[property=og:type]")
                ?.attr("content")

            val isSeries =
                type == "TVSeries" ||
                ogType == "video.tv_show" ||
                document.selectFirst(EPISODE_SELECTOR) != null

            if (isSeries) {
                val episodes = document.select(EPISODE_SELECTOR)
                    .mapNotNull { link ->
                        val episodeUrl = absoluteUrl(
                            url,
                            link.attr("href"),
                        ) ?: return@mapNotNull null

                        val match = EPISODE_PATH.find(episodeUrl)
                            ?: return@mapNotNull null

                        val seasonNumber = match.groupValues[1]
                            .toIntOrNull()
                            ?: return@mapNotNull null

                        val episodeNumber = match.groupValues[2]
                            .toIntOrNull()
                            ?: return@mapNotNull null

                        val episodeName = link.attr("title")
                            .trim()
                            .takeIf { it.isNotEmpty() }

                        val episodeDate = link
                            .selectFirst("time[datetime]")
                            ?.attr("datetime")
                            ?.takeIf { it.isNotBlank() }

                        newEpisode(episodeUrl) {
                            name = episodeName
                            season = seasonNumber
                            episode = episodeNumber

                            if (episodeDate != null) {
                                addDate(episodeDate)
                            }
                        }
                    }
                    .distinctBy { it.data }
                    .sortedWith(
                        compareBy(
                            { it.season ?: 0 },
                            { it.episode ?: 0 },
                        )
                    )

                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes,
                ) {
                    posterUrl = poster
                    plot = description
                    year = releaseYear
                    tags = genres
                }
            } else {
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    url,
                ) {
                    posterUrl = poster
                    plot = description
                    year = releaseYear
                    tags = genres
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false

        val countedCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        try {
            /*
             * Aynı sayfanın token ve cookie değerleri /px isteğinde
             * kullanılıyor. Cookie davranışı cihazda test edilmeli.
             */
            val page = app.get(
                data,
                headers = requestHeaders,
            )
            val document = page.document

            val csrf = document
                .selectFirst("meta[name=csrf-token]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

            val rawSources = document
                .selectFirst("#f_player[data-k]")
                ?.attr("data-k")
                ?.takeIf { it.isNotBlank() }

            if (rawSources != null) {
                val sources = try {
                    JSONArray(rawSources)
                } catch (error: Exception) {
                    JSONArray()
                }

                for (index in 0 until sources.length()) {
                    try {
                        val source = sources.optJSONObject(index)
                            ?: continue

                        val id = source.optString("i")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                        val signature = source.optString("s")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                        val headers = requestHeaders + mapOf(
                            "Accept" to "application/json",
                            "X-Requested-With" to "XMLHttpRequest",
                        ) + if (csrf != null) {
                            mapOf("X-CSRF-TOKEN" to csrf)
                        } else {
                            emptyMap()
                        }

                        val result = app.post(
                            "$mainUrl/px",
                            data = mapOf(
                                "i" to id,
                                "s" to signature,
                            ),
                            headers = headers,
                            cookies = page.cookies,
                            referer = data,
                        )

                        val playerUrl = JSONObject(result.text)
                            .optString("u")
                            .takeIf { it.isNotBlank() }
                            ?.let { absoluteUrl(data, it) }
                            ?: continue

                        resolvePlayer(
                            playerUrl = playerUrl,
                            referer = data,
                            sourceName = "Kaynak ${index + 1}",
                            subtitleCallback = subtitleCallback,
                            callback = countedCallback,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Bir kaynak başarısız olsa diğerleri denenir.
                    }
                }
            }

            // Doğrudan iframe bulunan sayfalar için yedek yol.
            for (iframe in document.select("iframe[src]")) {
                try {
                    val iframeUrl = absoluteUrl(data, iframe.attr("src"))
                        ?: continue

                    resolvePlayer(
                        playerUrl = iframeUrl,
                        referer = data,
                        sourceName = "Iframe",
                        subtitleCallback = subtitleCallback,
                        callback = countedCallback,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Sonraki iframe denenir.
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return found
        }

        return found
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (emitDirectVideo(playerUrl, referer, sourceName, callback)) {
            return
        }

        var extractorProducedLink = false

        try {
            loadExtractor(
                playerUrl,
                referer,
                subtitleCallback,
            ) { link ->
                extractorProducedLink = true
                callback(link)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Genel HTML çözümüne devam edilir.
        }

        if (extractorProducedLink) return

        /*
         * Tahmini yedek: iframe HTML'inde doğrudan medya URL'si,
         * video/source veya track varsa yakalar.
         * Şifreli ya da JS ile üretilen Vidmixi kaynakları için
         * çalışacağı doğrulanmadı.
         */
        val response = try {
            app.get(
                playerUrl,
                headers = requestHeaders,
                referer = referer,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return
        }

        val document = response.document
        val candidates = linkedSetOf<String>()

        document.select("video[src], video source[src], source[src]")
            .forEach { element ->
                absoluteUrl(playerUrl, element.attr("src"))
                    ?.let { candidates.add(it) }
            }

        val normalizedText = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        MEDIA_URL.findAll(normalizedText).forEach { match ->
            candidates.add(match.value)
        }

        candidates.forEach { candidate ->
            emitDirectVideo(
                candidate,
                playerUrl,
                sourceName,
                callback,
            )
        }

        document.select("track[src]").forEach { track ->
            val subtitleUrl = absoluteUrl(
                playerUrl,
                track.attr("src"),
            ) ?: return@forEach

            val language = track.attr("label")
                .ifBlank { track.attr("srclang") }
                .ifBlank { "Bilinmeyen" }

            subtitleCallback(
                newSubtitleFile(language, subtitleUrl)
            )
        }
    }

    private fun emitDirectVideo(
        url: String,
        referer: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val path = url.substringBefore("?").lowercase()

        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
            else -> return false
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name - $sourceName",
                url = url,
                type = type,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
