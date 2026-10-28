
package com.keyiflerolsun

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-6/" to "Yeni Eklenen Diziler",
        "$mainUrl/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-1/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle/" to "En Çok Beğenilenler",
        "$mainUrl/tur/aile-filmleri-izleyin-6/" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-3/" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmlerini-izleyin-4/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel-filmlerini-izle-1/" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmlerini-izleyin-2/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/komedi-filmlerini-izleyin-1/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmlerini-izle-2/" to "Korku Filmleri",
        "$mainUrl/tur/romantik-filmleri-izle-1/" to "Romantik Filmleri"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else {
            "${request.data.trimEnd('/')}/page/$page/"
        }
        val document = app.get(pageUrl).document

        val home = document.select(
            "div.section-content a.poster, a.poster"
        ).mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("strong.poster-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            selectFirst("img")?.let {
                it.attr("data-src").ifBlank {
                    it.attr("src")
                }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val anchor = document.selectFirst("a") ?: return@mapNotNull null

            val title = document.selectFirst("h4.title")?.text()?.trim()
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null

            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val poster = fixUrlNull(
                image?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("data-src")
            )

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster?.replace("/thumb/", "/list/")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.section-title")
            ?.text()?.trim()
            ?.substringBefore(" izle")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload")
                .lastOrNull()?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )

        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")
            ?.text()?.trim()?.toIntOrNull()

        val tvType = if (document.select("div.seasons").isEmpty()) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val description = document.selectFirst("article.post-info-content > p")
            ?.text()?.trim()

        val actors = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()
                ?: return@mapNotNull null
            Actor(actorName, it.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            })
        }

        val recommendations = document.select(
            "div.section-slider-container div.slider-slide"
        ).mapNotNull {
            val anchor = it.selectFirst("a") ?: return@mapNotNull null
            val recName = anchor.attr("title").ifBlank {
                anchor.selectFirst("img")?.attr("alt").orEmpty()
            }.takeIf { name -> name.isNotBlank() } ?: return@mapNotNull null

            val recHref = fixUrlNull(anchor.attr("href"))
                ?: return@mapNotNull null

            val img = anchor.selectFirst("img")
            val recPoster = fixUrlNull(
                img?.attr("data-src")?.takeIf { value -> value.isNotBlank() }
                    ?: img?.attr("src")
            )

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        val trailer = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")
            ?.substringAfter("trailer/", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/embed/$it" }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a")
                .mapNotNull {
                    val epName = it.selectFirst("h4")?.text()?.trim()
                        ?: return@mapNotNull null
                    val epHref = fixUrlNull(it.attr("href"))
                        ?: return@mapNotNull null

                    val epEpisode = Regex("""(\d+)\.?\s*Bölüm""")
                        .find(epName)?.groupValues?.get(1)?.toIntOrNull()

                    val epSeason = Regex("""(\d+)\.?\s*Sezon""")
                        .find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    newEpisode(epHref) {
                        name = epName
                        season = epSeason
                        episode = epEpisode
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun absoluteUrl(url: String, base: String): String? {
        val value = url.trim()
        if (value.isBlank() || value.startsWith("javascript:") ||
            value.startsWith("data:") || value == "#"
        ) return null

        return try {
            val resolved = java.net.URI(base).resolve(value).toString()
            resolved.takeIf {
                it.startsWith("http://", true) ||
                    it.startsWith("https://", true)
            }
        } catch (_: Exception) {
            fixUrlNull(value)
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
                )
            )

            val document = response.document
            var found = false

            // Önce doğrudan video etiketlerini dene.
            document.select("video source[src], video[src]").forEach { element ->
                val videoUrl = absoluteUrl(
                    element.attr("src"),
                    response.url
                ) ?: return@forEach

                if (videoUrl.contains(".m3u8", true) ||
                    videoUrl.contains(".mp4", true) ||
                    videoUrl.contains(".mpd", true)
                ) {
                    callback(
                        newExtractorLink(
                            source = source,
                            name = source,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8", true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                INFER_TYPE
                            }
                        ) {
                            quality = Qualities.Unknown.value
                            this.referer = response.url
                        }
                    )
                    found = true
                }
            }

            // Bazı kaynaklar adresi JavaScript içindeki file_link alanında tutar.
            val scripts = document.select("script").map { it.data() }
            scripts.forEach { script ->
                val unpacked = try {
                    getAndUnpack(script)
                } catch (_: Exception) {
                    script
                }

                val rawVideo = Regex(
                    """(?:file_link|file|src)\s*[:=]\s*["']([^"']+)["']"""
                ).find(unpacked)?.groupValues?.get(1)
                    ?: Regex(
                        """(?:file_link|file)\s*=\s*\\?["']([^"'\\]+)"""
                    ).find(unpacked)?.groupValues?.get(1)

                if (!rawVideo.isNullOrBlank()) {
                    val decoded = try {
                        base64Decode(rawVideo)
                    } catch (_: Exception) {
                        rawVideo
                    }

                    val videoUrl = absoluteUrl(decoded, response.url)
                    if (videoUrl != null &&
                        (videoUrl.contains(".m3u8", true) ||
                            videoUrl.contains(".mp4", true) ||
                            videoUrl.contains(".mpd", true))
                    ) {
                        callback(
                            newExtractorLink(
                                source = source,
                                name = source,
                                url = videoUrl,
                                type = if (videoUrl.contains(".m3u8", true)) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    INFER_TYPE
                                }
                            ) {
                                quality = Qualities.Unknown.value
                                this.referer = response.url
                            }
                        )
                        found = true
                    }
                }

                val tracks = Regex(
                    """tracks\s*:\s*\[(.*?)]""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(unpacked)?.groupValues?.get(1)

                if (!tracks.isNullOrBlank()) {
                    AppUtils.tryParseJson<List<SubSource>>("[$tracks]")
                        ?.filter { it.kind == "captions" }
                        ?.forEach { subtitle ->
                            val subtitleUrl = subtitle.file
                                ?.let { absoluteUrl(it, response.url) }
                                ?: return@forEach

                            subtitleCallback(
                                newSubtitleFile(
                                    lang = subtitle.label ?: "Türkçe",
                                    url = subtitleUrl
                                )
                            )
                        }
                }
            }

            found
        } catch (e: Exception) {
            Log.e("HDCH", "Kaynak açılamadı: $url", e)
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "loadLinks data=$data")

        val pageResponse = try {
            app.get(
                data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
                )
            )
        } catch (e: Exception) {
            Log.e("HDCH", "Film sayfası alınamadı: $data", e)
            return false
        }

        val document = pageResponse.document
        var found = false

        // Film sayfasındaki iframe doğrudan mevcutsa önce onu dene.
        document.select(
            ".video-container iframe[src], .video-container iframe[data-src]"
        ).forEach { iframeElement ->
            val iframeUrl = absoluteUrl(
                iframeElement.attr("src").ifBlank {
                    iframeElement.attr("data-src")
                },
                pageResponse.url
            ) ?: return@forEach

            Log.d("HDCH", "Doğrudan iframe: $iframeUrl")

            // Embed sayfasının kendi içinden çıkarılabilen video bağlantılarını dene.
            if (invokeLocalSource(
                    source = "HDFilmCehennemi",
                    url = iframeUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                found = true
            }
        }

        // Alternatif kaynak düğmelerini kullan.
        document.select("div.alternative-links").forEach { alternatives ->
            val langCode = alternatives.attr("data-lang")
                .uppercase().ifBlank { "TR" }

            alternatives.select("button.alternative-link[data-video]")
                .forEach { button ->
                    val videoID = button.attr("data-video").trim()
                    if (videoID.isBlank()) return@forEach

                    val sourceName = button.text()
                        .replace("(HDrip Xbet)", "")
                        .trim()
                        .ifBlank { "Video" }

                    val source = "$sourceName $langCode"
                    val endpoint = "$mainUrl/video/$videoID/"

                    val apiText = try {
                        app.get(
                            endpoint,
                            headers = mapOf(
                                "Accept" to "text/html,application/json,*/*",
                                "X-Requested-With" to "fetch",
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
                            ),
                            referer = data
                        ).text
                    } catch (e: Exception) {
                        Log.e("HDCH", "Alternatif kaynak alınamadı: $endpoint", e)
                        return@forEach
                    }

                    val apiDocument = Jsoup.parse(apiText, endpoint)

                    // JSON yanıtı veya HTML yanıtındaki iframe adresini bul.
                    val iframeUrl = apiDocument.selectFirst(
                        "iframe[src], iframe[data-src]"
                    )?.let { iframe ->
                        iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    }?.let { absoluteUrl(it, endpoint) }
                        ?: Regex(
                            """(?:data-src|src)\s*=\s*["']([^"']+\.mobi[^"']*)["']""",
                            RegexOption.IGNORE_CASE
                        ).find(apiText)?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?.let { absoluteUrl(it, endpoint) }
                        ?: Regex(
                            """https?:\\?/\\?/[^"'\\\s]+""",
                            RegexOption.IGNORE_CASE
                        ).find(apiText)?.value
                            ?.replace("\\/", "/")
                            ?.let { absoluteUrl(it, endpoint) }

                    if (iframeUrl != null) {
                        Log.d("HDCH", "$source -> $iframeUrl")

                        if (invokeLocalSource(
                                source = source,
                                url = iframeUrl,
                                referer = endpoint,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        ) {
                            found = true
                        }
                    } else {
                        Log.w(
                            "HDCH",
                            "Iframe bulunamadı: id=$videoID, cevap=${apiText.take(500)}"
                        )
                    }
                }
        }

        Log.d("HDCH", "loadLinks tamamlandı. Kaynak bulundu=$found")
        return found
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = emptyList()
    )
}
