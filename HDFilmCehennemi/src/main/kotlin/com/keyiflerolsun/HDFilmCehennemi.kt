package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.lang.Math.floorMod

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // CF bypass ayarları
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            val title = doc.select("title").text()

            if (title == "Just a moment..." || title == "Bir dakika lütfen...") {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/categories/nette-ilk-filmler/" to "Nette İlk Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler",
        "${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/" to "Aile Filmleri",
        "${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/" to "Aksiyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/" to "Animasyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/" to "Belgesel Filmleri",
        "${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/" to "Komedi Filmleri",
        "${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/" to "Korku Filmleri",
        "${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/" to "Romantik Filmleri",
        "${mainUrl}/load/page/sayfano/genres/suc-filmleri-izle-3/" to "Suç Filmleri",
        "${mainUrl}/load/page/sayfano/genres/tarih-filmleri-izle-4/" to "Tarih Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "X-Requested-With" to "fetch"
        )

        val response = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        val text = response.text  // FIX: .toString() yerine .text

        if (text.isBlank() || text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        val aa: HDFC? = try {
            objectMapper.readValue(text)
        } catch (e: Throwable) {
            Log.e("HDCH", "getMainPage JSON parse fail: ${e.message}")
            null
        } ?: return newHomePageResponse(request.name, emptyList())

        val document = Jsoup.parse(aa.html)
        val home = document.select("a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val score = this.selectFirst("span.imdb")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch"),
            interceptor = interceptor
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()
        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src")
        )
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()
        val actors = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            Actor(actorName, it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/", "")
            ?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
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
                this.score = Score.from10(rating)
                this.recommendations = recommendations
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
        Log.d("HDCH", "loadLinks data » $data")
        val document = app.get(data, interceptor = interceptor).document

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                if (videoID.isBlank()) {
                    Log.d("HDCH", "videoID boş: $source")
                    return@forEach
                }

                val apiGet = try {
                    app.get(
                        "${mainUrl}/video/$videoID/",
                        interceptor = interceptor,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text
                } catch (e: Throwable) {
                    Log.e("HDCH", "video API fail: ${e.message}")
                    ""
                }

                if (apiGet.isBlank()) return@forEach

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)
                    ?.replace("\\", "") ?: run {
                        Log.e("HDCH", "iframe bulunamadı, API yanıtı: ${apiGet.take(200)}")
                        return@forEach
                    }

                iframe = iframe.replace("{rapidrame_id}", "")
                Log.d("HDCH", "$source » $videoID » $iframe")

                // Built-in extractor dene, çalışıp çalışmadığını takip et
                var linkFound = false
                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    linkFound = true
                    callback(link)
                }

                try {
                    loadExtractor(iframe, data, subtitleCallback, wrappedCallback)
                } catch (e: Throwable) {
                    Log.e("HDCH", "loadExtractor fail: ${e.message}")
                }

                // Extractor başarısızsa manuel çıkarım
                if (!linkFound) {
                    Log.d("HDCH", "extractor başarısız, manuel deneniyor → $iframe")
                    tryManualExtract(iframe, data, source, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // loadExtractor başarısız olduğunda manuel çıkarım yapar
    private suspend fun tryManualExtract(
        iframeUrl: String,
        referer: String,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedResponse = app.get(iframeUrl, referer = referer, interceptor = interceptor)
            val embedText = embedResponse.text

            Log.d("HDCH", "[manuel] $iframeUrl uzunluk=${embedText.length} m3u8=${embedText.contains("m3u8")}")

            // Altyazılar
            Regex("""["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["']""")
                .findAll(embedText).forEach { match ->
                    subtitleCallback.invoke(SubtitleFile(match.groupValues[2], match.groupValues[1]))
                }

            // dc_hello
            Regex("""dc_hello\(\s*\[([^\]]+)\]\s*\)|dc_hello\(\s*["']([^"']+)["']\s*\)""")
                .find(embedText)?.let { match ->
                    val rawParts = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    val joined = rawParts.replace("\"", "").replace("'", "")
                        .replace(",", "").replace(" ", "")
                    val link = dcHello(joined)
                    if (link.startsWith("http")) {
                        Log.d("HDCH", "[manuel] dc_hello → $link")
                        M3u8Helper.generateM3u8(
                            source = "$name ($sourceLabel)",
                            streamUrl = link,
                            referer = iframeUrl
                        ).forEach(callback)
                        return
                    }
                }

            // dc_new
            Regex("""dc_new\(\s*\[([^\]]+)\]\s*\)""").find(embedText)?.let { match ->
                val parts = Regex("""["']([^"']+)["']""")
                    .findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
                if (parts.isNotEmpty()) {
                    val link = dcNew(parts)
                    if (link.startsWith("http")) {
                        Log.d("HDCH", "[manuel] dc_new → $link")
                        M3u8Helper.generateM3u8(
                            source = "$name ($sourceLabel)",
                            streamUrl = link,
                            referer = iframeUrl
                        ).forEach(callback)
                        return
                    }
                }
            }

            // Doğrudan m3u8/mp4
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedText)?.let { match ->
                Log.d("HDCH", "[manuel] direct → ${match.groupValues[1]}")
                M3u8Helper.generateM3u8(
                    source = "$name ($sourceLabel)",
                    streamUrl = match.groupValues[1],
                    referer = iframeUrl
                ).forEach(callback)
                return
            }

            Log.e("HDCH", "[manuel] hiçbir yöntem işe yaramadı")
        } catch (e: Throwable) {
            Log.e("HDCH", "[manuel] hata: ${e.message}")
        }
    }

    private fun dcHello(base64Input: String): String {
        return try {
            val decodedOnce = base64Decode(base64Input)
            val reversedString = decodedOnce.reversed()
            val decodedTwice = base64Decode(reversedString)
            when {
                decodedTwice.contains("+") -> decodedTwice.substringAfterLast("+")
                decodedTwice.contains(" ") -> decodedTwice.substringAfterLast(" ")
                decodedTwice.contains("|") -> decodedTwice.substringAfterLast("|")
                else -> decodedTwice
            }
        } catch (e: Throwable) { "" }
    }

    private fun dcNew(parts: List<String>): String {
        return try {
            val value = parts.joinToString("")
            val decodedBytes = base64DecodeArray(value)
            val rot13Bytes = decodedBytes.map { byte ->
                val c = byte.toInt() and 0xFF
                when (c) {
                    in 'a'.code..'z'.code -> (((c - 'a'.code + 13) % 26) + 'a'.code).toByte()
                    in 'A'.code..'Z'.code -> (((c - 'A'.code + 13) % 26) + 'A'.code).toByte()
                    else -> byte
                }
            }.toByteArray()
            val reversedBytes = rot13Bytes.reversedArray()
            val unmixedBytes = ByteArray(reversedBytes.size)
            for (i in reversedBytes.indices) {
                val charCode = reversedBytes[i].toInt() and 0xFF
                val offset = 399756995 % (i + 5)
                unmixedBytes[i] = floorMod(charCode - offset, 256).toByte()
            }
            String(unmixedBytes, Charsets.ISO_8859_1)
        } catch (e: Throwable) { "" }
    }

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html") val html: String = "",
        @JsonProperty("meta") val meta: Meta? = null
    )

    data class Meta(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("canonical") val canonical: Boolean? = null,
        @JsonProperty("keywords") val keywords: Boolean? = null
    )
}
