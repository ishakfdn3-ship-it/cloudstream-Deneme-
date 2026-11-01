// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    // ! Site adresi sık değişiyor. Çalışan güncel adresi buraya yaz.
    override var mainUrl = "https://dizipal1591.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

    // Cloudflare "Just a moment" sayfasını otomatik aşmak için
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024 * 1024).string()
            val doc = Jsoup.parse(body)

            if (response.code == 403 || response.code == 503 ||
                doc.title().contains("Just a moment", true) ||
                body.contains("cf-browser-verification") ||
                body.contains("challenge-platform")
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/son-bolumler" to "Son Bölümler",
        "$mainUrl/diziler" to "Yeni Diziler",
        "$mainUrl/filmler" to "Yeni Filmler",
        "$mainUrl/koleksiyon/netflix" to "Netflix",
        "$mainUrl/koleksiyon/exxen" to "Exxen",
        "$mainUrl/koleksiyon/blutv" to "BluTV",
        "$mainUrl/koleksiyon/disney" to "Disney+",
        "$mainUrl/koleksiyon/amazon-prime" to "Amazon Prime",
        "$mainUrl/koleksiyon/tod-bein" to "TOD (beIN)",
        "$mainUrl/koleksiyon/gain" to "Gain",
        "$mainUrl/tur/mubi" to "Mubi",
        "$mainUrl/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        "$mainUrl/diziler?kelime=&durum=&tur=5&type=&siralama=" to "Bilimkurgu Dizileri",
        "$mainUrl/tur/bilimkurgu" to "Bilimkurgu Filmleri",
        "$mainUrl/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "$mainUrl/tur/komedi" to "Komedi Filmleri",
        "$mainUrl/diziler?kelime=&durum=&tur=4&type=&siralama=" to "Belgesel Dizileri",
        "$mainUrl/tur/belgesel" to "Belgesel Filmleri",
        "$mainUrl/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        "$mainUrl/tur/erotik" to "Erotik Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(
            request.data,
            headers = browserHeaders,
            referer = "$mainUrl/",
            interceptor = interceptor
        )
        val document = response.document

        Log.d("DZP", "mainPage ${request.name} » HTTP ${response.code} | url=${response.url}")

        val home = if (request.data.contains("/diziler/son-bolumler")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() }
        } else {
            var items = document.select("article.type2 ul li").mapNotNull { it.diziler() }

            // Yedek seçiciler: site HTML'i değişmişse boş kalmasın
            if (items.isEmpty()) {
                items = document.select("div.items li, ul.items li, div.list li, article li")
                    .mapNotNull { it.diziler() }
            }
            if (items.isEmpty()) {
                items = document.select("a[href*=/dizi/], a[href*=/film/]")
                    .mapNotNull { it.parent()?.diziler() ?: it.diziler() }
                    .distinctBy { it.url }
            }
            items
        }

        Log.d("DZP", "mainPage ${request.name} » ${home.size} içerik")

        if (home.isEmpty()) {
            Log.d("DZP", "HTML başı » ${document.html().take(1500)}")

            // Teşhis satırı: loga bakmadan ekranda sebebi göster
            val debugTitle = "⚠ HTTP ${response.code} | ${document.title().take(40)} | " +
                "${document.html().length} bayt | ${response.url.take(50)}"
            val counts = "li:${document.select("li").size} " +
                "article:${document.select("article").size} " +
                "img:${document.select("img").size} " +
                "/dizi/:${document.select("a[href*=/dizi/]").size} " +
                "/film/:${document.select("a[href*=/film/]").size}"

            val containers = document.select("article, section, main, ul")
                .take(6)
                .joinToString(" ; ") { it.tagName() + "." + it.className().take(25) }

            val bodyStart = document.body().text().take(120)

            val debugItems = listOf(
                debugTitle,
                "URL: ${request.data.take(70)}",
                counts,
                "Kapsayıcılar: $containers",
                "Metin: $bodyStart"
            ).map { newMovieSearchResponse(it, request.data, TvType.Movie) }

            return newHomePageResponse(request.name, debugItems, hasNext = false)
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name = selectFirst("div.name")?.text() ?: return null
        val episode = selectFirst("div.episode")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title = "$name $episode"
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = imgUrl()

        return newTvSeriesSearchResponse(
            title,
            href.substringBefore("/sezon"),
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("span.title")?.text()
            ?: selectFirst("h2, h3, h4, h5")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: selectFirst("a")?.attr("title")
            ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = imgUrl()

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Lazy-load görselleri için data-src / data-lazy-src desteği
    private fun Element.imgUrl(): String? {
        val img = selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title = title
        val href = "$mainUrl$url"
        val posterUrl = poster

        return if (type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "$mainUrl/api/search-autocomplete",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to browserHeaders.getValue("User-Agent")
            ),
            referer = "$mainUrl/",
            data = mapOf("query" to query),
            interceptor = interceptor
        )

        val searchItemsMap =
            jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)

        return searchItemsMap.values.map { it.toPostSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = browserHeaders,
            referer = "$mainUrl/",
            interceptor = interceptor
        ).document

        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div")
            .text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags = document.selectXpath("//div[text()='Türler']//following-sibling::div")
            .text().trim().split(" ").map { it.trim() }
        val duration = Regex("(\\d+)")
            .find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())
            ?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("div.cover h5")?.text() ?: return null

            val episodes = document.select("div.episode-item").mapNotNull {
                val epName = it.selectFirst("div.name")?.text()?.trim()
                    ?: return@mapNotNull null
                val epHref = fixUrlNull(it.selectFirst("a")?.attr("href"))
                    ?: return@mapNotNull null
                val episodeText = it.selectFirst("div.episode")?.text()?.trim().orEmpty()
                val epEpisode = episodeText.split(" ").getOrNull(2)
                    ?.replace(".", "")?.toIntOrNull()
                val epSeason = episodeText.split(" ").getOrNull(0)
                    ?.replace(".", "")?.toIntOrNull()

                newEpisode(epHref) {
                    name = epName
                    episode = epEpisode
                    season = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        }

        val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "data » $data")

        val document = app.get(
            data,
            headers = browserHeaders,
            referer = "$mainUrl/",
            interceptor = interceptor
        ).document

        val iframe = document.selectFirst(".series-player-container iframe")?.attr("src")
            ?: document.selectFirst("div#vast_new iframe")?.attr("src")
            ?: return false

        Log.d("DZP", "iframe » $iframe")

        val iSource = app.get(iframe, referer = "$mainUrl/").text
        val m3uLink = Regex("""file:"([^"]+)""")
            .find(iSource)?.groupValues?.get(1)

        if (m3uLink == null) {
            Log.d("DZP", "iSource » $iSource")
            return loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        val subtitles = Regex(""""subtitle":"([^"]+)""")
            .find(iSource)?.groupValues?.get(1)

        if (!subtitles.isNullOrBlank()) {
            subtitles.split(",").forEach {
                val subLang = it.substringAfter("[").substringBefore("]")
                val subUrl = it.replace("[$subLang]", "")

                subtitleCallback(
                    newSubtitleFile(
                        lang = subLang,
                        url = fixUrl(subUrl)
                    )
                )
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }
}
