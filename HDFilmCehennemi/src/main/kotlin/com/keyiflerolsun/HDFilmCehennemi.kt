package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
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
            val response = chain.proceed(chain.request())
            val title = Jsoup.parse(response.peekBody(1024 * 1024).string()).select("title").text()
            if (title == "Just a moment..." || title == "Bir dakika lütfen...") {
                return killer.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Diziler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val url = request.data.replace("sayfano", page.toString())
        val text = try {
            app.get(url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                    "X-Requested-With" to "fetch"
                ),
                referer = mainUrl,
                interceptor = interceptor
            ).text
        } catch (e: Throwable) {
            return newHomePageResponse(request.name, emptyList())
        }

        if (text.isBlank() || text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        val data: HDFC = try {
            mapper.readValue(text, HDFC::class.java)
        } catch (e: Throwable) {
            Log.e("HDCH", "json: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        val home = Jsoup.parse(data.html).select("a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        return newMovieSearchResponse(attr("title"), href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
            this.score = Score.from10(selectFirst("span.imdb")?.text()?.trim())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val text = try {
            app.get("${mainUrl}/search?q=$query",
                headers = mapOf("X-Requested-With" to "fetch"),
                interceptor = interceptor
            ).text
        } catch (e: Throwable) { return emptyList() }

        val data: Results = try {
            mapper.readValue(text, Results::class.java)
        } catch (e: Throwable) { return emptyList() }

        val out = mutableListOf<SearchResponse>()
        for (html in data.results) {
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h4.title")?.text() ?: continue
            val href = fixUrlNull(doc.selectFirst("a")?.attr("href")) ?: continue
            val poster = fixUrlNull(doc.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(doc.selectFirst("img")?.attr("data-src"))
            out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster?.replace("/thumb/", "/list/")
            })
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = Jsoup.parse(app.get(url, interceptor = interceptor).text)
        val title = doc.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(doc.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = doc.select("div.post-info-genres a").map { it.text() }
        val year = doc.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val plot = doc.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score = doc.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()
        val actors = doc.select("div.post-info-cast a").mapNotNull {
            val n = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            Actor(n, it.select("img").attr("data-src"))
        }

        return if (doc.select("div.seasons").isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(score); addActors(actors)
            }
        } else {
            val eps = doc.select("div.seasons-tab-content a").mapNotNull {
                val n = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val h = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                newEpisode(h) {
                    this.name = n
                    this.season = Regex("""(\d+)\. ?Sezon""").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    this.episode = Regex("""(\d+)\. ?Bölüm""").find(n)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(score); addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.parse(app.get(data, interceptor = interceptor).text)

        doc.select("div.alternative-links").forEach { group ->
            val lang = group.attr("data-lang").uppercase()
            group.select("button.alternative-link").forEach { btn ->
                val vid = btn.attr("data-video")
                if (vid.isBlank()) return@forEach

                val src = btn.text().replace("(HDrip Xbet)", "").trim() + " $lang"
                val api = try {
                    app.get("${mainUrl}/video/$vid/",
                        headers = mapOf("X-Requested-With" to "fetch"),
                        referer = data, interceptor = interceptor
                    ).text
                } catch (e: Throwable) { "" }
                if (api.isBlank()) return@forEach

                var iframe = Regex("""data-src=\\"([^"]+)""").find(api)?.groupValues?.get(1)
                    ?.replace("\\", "") ?: return@forEach
                iframe = iframe.replace("{rapidrame_id}", "")
                Log.d("HDCH", "$src → $iframe")

                var found = false
                val wrap: (ExtractorLink) -> Unit = { found = true; callback(it) }
                try { loadExtractor(iframe, data, subtitleCallback, wrap) } catch (_: Throwable) {}

                if (!found) {
                    manualExtract(iframe, data, src, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun manualExtract(
        url: String, ref: String, label: String,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = ref, interceptor = interceptor).text
            val doc = Jsoup.parse(html)
            var text = html

            for (s in doc.select("script")) {
                val d = try { s.data() } catch (_: Throwable) { continue }
                if (d.contains("eval(function(p,a,c,k,e,d)")) {
                    text = try {
                        val c = Class.forName("com.lagradost.common.JsUnpacker")
                        (c.getMethod("unpack").invoke(c.getConstructor(String::class.java).newInstance(d)) as? String) ?: d
                    } catch (_: Throwable) { d }
                    break
                }
            }

            Regex("""dc_hello\(\s*\[([^\]]+)\]\s*\)""").find(text)?.let {
                val v = dcHello(it.groupValues[1].replace("\"", "").replace(",", ""))
                if (v.startsWith("http")) { emit(v, url, label, cb); return }
            }
            Regex("""dc_new\(\s*\[([^\]]+)\]\s*\)""").find(text)?.let {
                val p = Regex("""["']([^"']+)["']""").findAll(it.groupValues[1]).map { m -> m.groupValues[1] }.toList()
                val v = dcNew(p)
                if (v.startsWith("http")) { emit(v, url, label, cb); return }
            }
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(text)?.let {
                emit(it.groupValues[1], url, label, cb)
            }
        } catch (_: Throwable) {}
    }

    private fun emit(link: String, ref: String, label: String, cb: (ExtractorLink) -> Unit) {
        try {
            cb.invoke(newExtractorLink(
                source = "$name ($label)",
                name = "$name ($label)",
                url = link,
                type = INFER_TYPE
            ) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
            })
        } catch (_: Throwable) {}
    }

    private fun dcHello(s: String): String = try {
        val d = String(Base64.decode(String(Base64.decode(s, Base64.DEFAULT), Charsets.ISO_8859_1).reversed(), Base64.DEFAULT), Charsets.ISO_8859_1)
        listOf("+", " ", "|").find { d.contains(it) }?.let { d.substringAfterLast(it) } ?: d
    } catch (_: Throwable) { "" }

    private fun dcNew(p: List<String>): String = try {
        val b = Base64.decode(p.joinToString(""), Base64.DEFAULT)
        val r = b.map { x ->
            val c = x.toInt() and 0xFF
            when (c) {
                in 'a'.code..'z'.code -> (((c - 'a'.code + 13) % 26) + 'a'.code).toByte()
                in 'A'.code..'Z'.code -> (((c - 'A'.code + 13) % 26) + 'A'.code).toByte()
                else -> x
            }
        }.toByteArray().reversedArray()
        String(ByteArray(r.size) { i ->
            floorMod((r[i].toInt() and 0xFF) - (399756995 % (i + 5)), 256).toByte()
        }, Charsets.ISO_8859_1)
    } catch (_: Throwable) { "" }

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String = "")
}
