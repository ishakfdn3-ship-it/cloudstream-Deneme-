package com.keyiflerolsun

import android.util.Base64
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

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val peeked = response.peekBody(1024 * 1024).string()
            val title = Jsoup.parse(peeked).select("title").text()
            if (title == "Just a moment..." || title == "Bir dakika lütfen...") {
                return killer.intercept(chain)
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
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "X-Requested-With" to "fetch"
        )

        val text = try {
            app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor).text
        } catch (e: Throwable) {
            Log.e("HDCH", "getMainPage fetch: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        if (text.isBlank() || text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        val aa: HDFC = try {
            mapper.readValue(text)
        } catch (e: Throwable) {
            Log.e("HDCH", "getMainPage json: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

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
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val text = try {
            app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf("X-Requested-With" to "fetch"),
                interceptor = interceptor
            ).text
        } catch (e: Throwable) {
            return emptyList()
        }

        val response: Results = try {
            mapper.readValue(text)
        } catch (e: Throwable) {
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        for (html in response.results) {
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h4.title")?.text() ?: continue
            val href = fixUrlNull(doc.selectFirst("a")?.attr("href")) ?: continue
            val posterUrl = fixUrlNull(doc.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(doc.selectFirst("img")?.attr("data-src"))

            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                }
            )
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = Jsoup.parse(
            app.get(url, interceptor = interceptor).text
        )

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
        Log.d("HDCH", "loadLinks » $data")
        val document = Jsoup.parse(app.get(data, interceptor = interceptor).text)

        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val source = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video")
                if (videoID.isBlank()) return@forEach

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
                    Log.e("HDCH", "api fail: ${e.message}")
                    ""
                }
                if (apiGet.isBlank()) return@forEach

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)
                    ?.replace("\\", "") ?: run {
                        Log.e("HDCH", "iframe yok: ${apiGet.take(300)}")
                        return@forEach
                    }
                iframe = iframe.replace("{rapidrame_id}", "")
                Log.d("HDCH", "$source » $videoID » $iframe")

                var found = false
                val wrapped: (ExtractorLink) -> Unit = { link ->
                    found = true
                    callback(link)
                }
                try {
                    loadExtractor(iframe, data, subtitleCallback, wrapped)
                } catch (e: Throwable) {
                    Log.e("HDCH", "loadExtractor: ${e.message}")
                }

                if (!found) {
                    Log.d("HDCH", "manuel: $iframe")
                    manualExtract(iframe, data, source, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun manualExtract(
        iframeUrl: String,
        referer: String,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(iframeUrl, referer = referer, interceptor = interceptor).text
            val doc = Jsoup.parse(html)
            Log.d("HDCH", "[manuel] len=${html.length} m3u8=${html.contains("m3u8")} dc_hello=${html.contains("dc_hello")} dc_new=${html.contains("dc_new")}")

            Regex("""["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt))["']\s*,\s*["']?label["']?\s*:\s*["']([^"']+)["']""")
                .findAll(html).forEach { m ->
                    subtitleCallback.invoke(SubtitleFile(m.groupValues[2], m.groupValues[1]))
                }

            var unpacked = html
            for (script in doc.select("script")) {
                val data = try { "" + script.data() } catch (e: Throwable) { continue }
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    unpacked = try {
                        val clazz = Class.forName("com.lagradost.common.JsUnpacker")
                        val ctor = clazz.getConstructor(String::class.java)
                        val inst = ctor.newInstance(data)
                        (clazz.getMethod("unpack").invoke(inst) as? String) ?: data
                    } catch (e: Throwable) { data }
                    break
                }
            }

            Regex("""dc_hello\(\s*\[([^\]]+)\]\s*\)|dc_hello\(\s*["']([^"']+)["']\s*\)""")
                .find(unpacked)?.let { m ->
                    val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    val joined = raw.replace("\"", "").replace("'", "").replace(",", "").replace(" ", "")
                    val link = dcHello(joined)
                    if (link.startsWith("http")) {
                        emitLink(link, iframeUrl, sourceLabel, callback)
                        return
                    }
                }

            Regex("""dc_new\(\s*\[([^\]]+)\]\s*\)""").find(unpacked)?.let { m ->
                val parts = Regex("""["']([^"']+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                if (parts.isNotEmpty()) {
                    val link = dcNew(parts)
                    if (link.startsWith("http")) {
                        emitLink(link, iframeUrl, sourceLabel, callback)
                        return
                    }
                }
            }

            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.let { m ->
                emitLink(m.groupValues[1], iframeUrl, sourceLabel, callback)
                return
            }
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.let { m ->
                emitLink(m.value, iframeUrl, sourceLabel, callback)
            }
        } catch (e: Throwable) {
            Log.e("HDCH", "[manuel] ${e.message}")
        }
    }

    private fun emitLink(
        link: String,
        iframeUrl: String,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            callback.invoke(
                newExtractorLink(
                    source = "$name ($sourceLabel)",
                    name = "$name ($sourceLabel)",
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d("HDCH", "[manuel] ✅ $link")
        } catch (e: Throwable) {
            Log.e("HDCH", "emitLink ${e.message}")
        }
    }

    private fun dcHello(input: String): String {
        return try {
            val d1 = String(Base64.decode(input, Base64.DEFAULT), Charsets.ISO_8859_1)
            val d2 = String(Base64.decode(d1.reversed(), Base64.DEFAULT), Charsets.ISO_8859_1)
            when {
                d2.contains("+") -> d2.substringAfterLast("+")
                d2.contains(" ") -> d2.substringAfterLast(" ")
                d2.contains("|") -> d2.substringAfterLast("|")
                else -> d2
            }
        } catch (e: Throwable) { "" }
    }

    private fun dcNew(parts: List<String>): String {
        return try {
            val joined = parts.joinToString("")
            val decoded = Base64.decode(joined, Base64.DEFAULT)
            val rot13 = decoded.map { b ->
                val c = b.toInt() and 0xFF
                when (c) {
                    in 'a'.code..'z'.code -> (((c - 'a'.code + 13) % 26) + 'a'.code).toByte()
                    in 'A'.code..'Z'.code -> (((c - 'A'.code + 13) % 26) + 'A'.code).toByte()
                    else -> b
                }
            }.toByteArray()
            val rev = rot13.reversedArray()
            val out = ByteArray(rev.size)
            for (i in rev.indices) {
                val cc = rev[i].toInt() and 0xFF
                val off = 399756995 % (i + 5)
                out[i] = floorMod(cc - off, 256).toByte()
            }
            String(out, Charsets.ISO_8859_1)
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
