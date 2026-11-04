package com.example.filmmodu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmModu : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Kategori slug'ları doğrulanmadı. Sitede yoksa sil.
    override val mainPage = mainPageOf(
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/filmizle/aksiyon" to "Aksiyon",
        "$mainUrl/filmizle/komedi" to "Komedi",
        "$mainUrl/filmizle/dram" to "Dram",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, referer = mainUrl).document
        val items = doc.select("article.movie_box a.image").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val card = closest("article") ?: this
        val title = card.selectFirst("h2")?.text()?.trim()
            ?: attr("title").ifBlank { null }
            ?: return null
        val poster = card.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        }

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // Arama URL'si doğrulanmadı. Sitede arama sonucu sayfası farklıysa burayı düzelt.
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama?q=$encoded", referer = mainUrl).document
        val items = doc.select("article.movie_box a.image").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(items, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" izle")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")

        return if (url.contains("/dizi/")) {
            val episodes = doc.select("a.dz-bolum").mapNotNull { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epName = ep.selectFirst("b.dz-ad")?.text()?.trim() ?: ep.text().trim()
                val season = Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val number = Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                Episode(
                    data = epHref,
                    name = epName,
                    season = season,
                    episode = number
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data, referer = mainUrl).text
        val doc = Jsoup.parse(pageHtml)
        val playerReferer = "https://vidmixi.com/"
        val candidates = linkedSetOf<String>()

        // iframe ve data-* attribute'larındaki adaylar
        doc.select("iframe, [data-frame], [data-src], [data-video]").forEach { el ->
            listOf("src", "data-src", "data-frame", "data-video").forEach { attr ->
                el.attr(attr).takeIf { it.isNotBlank() }?.let { fixUrlNull(it)?.let(candidates::add) }
            }
        }

        // Sayfa kaynağındaki gömülü linkler
        Regex("""https?:[\\/]+[^"'\s<>]+""").findAll(pageHtml).forEach {
            val u = it.value.replace("\\/", "/")
            if (u.contains("vidmixi") || u.contains("/embed/") || u.contains("/process/") || u.contains(".m3u8")) {
                candidates.add(u)
            }
        }

        var found = false

        for (link in candidates) {
            // Doğrudan HLS / process linkleri
            if (link.contains("/process/") || link.contains(".m3u8")) {
                callback(
                    newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer
                        this.headers = mapOf(
                            "Origin" to "https://vidmixi.com",
                            "Referer" to playerReferer,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 13)"
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
                continue
            }

            // vidmixi embed sayfasında düz m3u8 var mı
            if (link.contains("vidmixi")) {
                val embedHtml = runCatching { app.get(link, referer = data).text }.getOrNull()
                if (embedHtml != null) {
                    Regex("""https?:[\\/]+[^"'\s<>]+?\.m3u8[^"'\s<>]*""").findAll(embedHtml).forEach { m ->
                        val hls = m.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
                                this.referer = playerReferer
                                this.headers = mapOf(
                                    "Origin" to "https://vidmixi.com",
                                    "Referer" to playerReferer
                                )
                            }
                        )
                        found = true
                    }
                }
            }

            // Geri kalan host'lar için CloudStream'in kendi extractor'ları
            if (loadExtractor(link, data, subtitleCallback, callback)) found = true
        }

        return found
    }
}
