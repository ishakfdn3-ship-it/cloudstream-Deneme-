package com.example.filmmodu

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class FilmModu : MainAPI() {

    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmizle/aksiyon-filmleri-izle" to "Aksiyon",
        "$mainUrl/filmizle/komedi-filmleri-hd-izle" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri-izle" to "Korku",
        "$mainUrl/filmizle/dram-filmleri-izle" to "Dram",
        "$mainUrl/filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu",
        "$mainUrl/filmizle/animasyon-filmleri-izle" to "Animasyon",
        "$mainUrl/filmizle/turkce-dublaj-filmler-hd-izle" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-hd-izle" to "Türkçe Altyazılı",
        "$mainUrl/populer-filmler" to "Popüler",
        "$mainUrl/trend-filmler" to "Trend"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfalama parametresi (?page=) doğrulanmadı; sonuç boşsa veya
        // 10. sayfadaysak devam etmiyoruz.
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val items = parseCards(app.get(url).document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && page < 10)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama uç noktası (/arama/oneri?q=) gözlemlendi ama yanıt biçimi
        // doğrulanmadı. Sonuç gelmezse bu satırı düzeltmek gerekir.
        val encoded = URLEncoder.encode(query, "UTF-8")
        return parseCards(app.get("$mainUrl/arama/oneri?q=$encoded").document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val desc = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val releaseYear = doc.select("a[href*='/yil/']").firstNotNullOfOrNull { link ->
            Regex("""/yil/(\d{4})""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }

        if (url.contains("/dizi/") && !url.contains("/sezon-")) {
            val episodes = doc.select("a.dz-bolum").mapNotNull { el ->
                val epUrl = absUrl(url, el.attr("href")) ?: return@mapNotNull null
                val match = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(epUrl)
                    ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull()
                val episode = match.groupValues[2].toIntOrNull()
                val epName = el.selectFirst(".dz-ad")?.text()?.trim()

                newEpisode(epUrl, initializer = {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = desc
                year = releaseYear
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = desc
            year = releaseYear
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Oynatıcı iframe'i sayfa ilk açıldığında boş geliyor; bu yüzden
        // bu bölüm çoğu içerikte bağlantı bulamayabilir.
        val doc = app.get(data).document
        var found = false

        doc.select("#f_player iframe[src], #fm_cerceve iframe[src]").forEach { frame ->
            val src = absUrl(data, frame.attr("src")) ?: return@forEach
            if (loadExtractor(src, data, subtitleCallback, callback)) found = true
        }

        return found
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("article.movie_box a.image, a.search_s").mapNotNull { anchor ->
            val href = absUrl(mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            val title = (anchor.selectFirst("h2") ?: anchor.selectFirst(".title"))
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val img = anchor.selectFirst("img")
            val poster = img?.let {
                absUrl(mainUrl, it.attr("data-src").ifBlank { it.attr("src") })
            }

            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    private fun absUrl(base: String, href: String?): String? {
        if (href.isNullOrBlank() || href.startsWith("data:")) return null
        return try {
            URI(base).resolve(href).toString()
        } catch (_: Exception) {
            null
        }
    }
}
