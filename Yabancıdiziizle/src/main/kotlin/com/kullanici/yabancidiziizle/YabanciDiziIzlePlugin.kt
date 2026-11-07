package com.kullanici.yabancidiziizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class YabanciDiziIzle : MainAPI() {
    override var mainUrl = "https://yabancidiziizle.in"
    override var name = "YabanciDiziIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/#diziler" to "Son Eklenen Diziler",
        "$mainUrl/#one-cikanlar" to "Öne Çıkan Filmler",
        "$mainUrl/film-arsivi/" to "Tüm Filmler",
        "$mainUrl/dizi-arsivi/" to "Tüm Diziler",
        "$mainUrl/film-kategori/aksiyon/" to "Aksiyon Filmleri",
        "$mainUrl/film-kategori/bilim-kurgu/" to "Bilim Kurgu Filmleri",
        "$mainUrl/film-kategori/dram/" to "Dram Filmleri",
        "$mainUrl/film-kategori/komedi/" to "Komedi Filmleri",
        "$mainUrl/film-kategori/korku/" to "Korku Filmleri",
        "$mainUrl/film-kategori/macera/" to "Macera Filmleri"
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*-\s*Yabanc[ıi]diziizle\.in.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Archives$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Tüm\s+Bölümler(\s+izle)?.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractPoster(img: Element?): String? {
        if (img == null) return null
        val candidate = listOf("data-src", "data-lazy-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull {
                it.isNotBlank() &&
                    !it.startsWith("data:") &&
                    !it.endsWith("lazy.png", ignoreCase = true)
            }
        return fixUrlNull(candidate)
    }

    private fun isIgnoredIframe(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("youtube.com/embed/") ||
            lower.contains("google.com")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseData = request.data.substringBefore("#")
        val fragment = request.data.substringAfter("#", "")

        val targetUrl = if (page <= 1) {
            baseData
        } else {
            val cleanBase = baseData.trimEnd('/')
            "$cleanBase/page/$page/"
        }

        val doc = app.get(targetUrl, referer = "$mainUrl/").document

        val items: List<SearchResponse> = when (fragment) {
            "diziler" -> {
                if (page > 1) {
                    emptyList()
                } else {
                    val sliderSeries = doc.select("div.diziler div.list-series div.episode-box")
                        .mapNotNull { it.toSeriesSearchResult() }
                    val sidebarSeries = doc.select("ul#listul li.cat-item a[href]")
                        .mapNotNull { a ->
                            val href = fixUrlNull(a.attr("href").takeIf { it.isNotBlank() }) ?: return@mapNotNull null
                            val title = cleanTitle(a.text())
                            if (title.isBlank()) return@mapNotNull null
                            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                        }
                    (sliderSeries + sidebarSeries).distinctBy { it.url }
                }
            }
            "one-cikanlar" -> {
                if (page > 1) {
                    emptyList()
                } else {
                    doc.select("section.cmfp-recommended article.cmfp-card, section.cmfp-upcoming article.cmfp-card")
                        .mapNotNull { it.toCmfpCardSearchResult() }
                        .distinctBy { it.url }
                }
            }
            else -> parseListingCards(doc)
        }

        val hasNext = fragment.isEmpty() && items.isNotEmpty() && (
            doc.selectFirst("link[rel=next], a.next, a.nextpostslink, div.pagination a:contains(Sonraki)") != null
        )

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseListingCards(doc: Document): List<SearchResponse> {
        val movies = doc.select("div.listmovie div.movie-box").mapNotNull { it.toMovieSearchResult() }
        val series = doc.select("div.list-series div.episode-box").mapNotNull { it.toSeriesSearchResult() }
        val cmfpCards = doc.select("article.cmfp-card").mapNotNull { it.toCmfpCardSearchResult() }
        val episodes = doc.select("div.list-episodes div.episode-box").mapNotNull { it.toEpisodeBoxSearchResult() }

        return (movies + series + cmfpCards + episodes).distinctBy { it.url }
    }

    private fun Element.toMovieSearchResult(): SearchResponse? {
        val linkEl = selectFirst("div.film-ismi a[href], div.poster a[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href").takeIf { it.isNotBlank() }) ?: return null
        val rawTitle = selectFirst("div.film-ismi a")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: linkEl.text()
        val title = cleanTitle(rawTitle).takeIf { s -> s.isNotBlank() } ?: return null
        val poster = extractPoster(selectFirst("div.poster img, img"))
        val scoreText = selectFirst("div.film-ust")?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(scoreText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.score = Score.from10(scoreText)
            }
        }
    }

    private fun Element.toSeriesSearchResult(): SearchResponse? {
        val linkEl = selectFirst("div.serie-name a[href], div.poster a[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href").takeIf { it.isNotBlank() }) ?: return null
        val rawTitle = selectFirst("div.serie-name a")?.text()
            ?: selectFirst("img")?.attr("title")
            ?: linkEl.text()
        val title = cleanTitle(rawTitle).takeIf { s -> s.isNotBlank() } ?: return null
        val poster = extractPoster(selectFirst("div.poster img, img"))
        val scoreText = selectFirst("div.episode-date")?.text()?.replace("IMDb:", "", ignoreCase = true)?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.score = Score.from10(scoreText)
        }
    }

    private fun Element.toCmfpCardSearchResult(): SearchResponse? {
        val linkEl = selectFirst("h3 a[href], a.cmfp-poster[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href").takeIf { it.isNotBlank() }) ?: return null
        val rawTitle = selectFirst("h3 a")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: linkEl.text()
        val title = cleanTitle(rawTitle).takeIf { s -> s.isNotBlank() } ?: return null
        val poster = extractPoster(selectFirst("img"))

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

    private fun Element.toEpisodeBoxSearchResult(): SearchResponse? {
        val linkEl = selectFirst("div.serie-name a[href], div.poster a[href], a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href").takeIf { it.isNotBlank() }) ?: return null
        val serieName = cleanTitle(selectFirst("div.serie-name a")?.text().orEmpty())
        val epName = selectFirst("div.episode-name a")?.text()?.trim().orEmpty()
        val title = listOf(serieName, epName).filter { it.isNotBlank() }.joinToString(" - ")
            .takeIf { s -> s.isNotBlank() } ?: return null
        val poster = extractPoster(selectFirst("div.poster img, img"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery", referer = "$mainUrl/").document

        val cardResults = parseListingCards(doc)
        if (cardResults.isNotEmpty()) return cardResults

        return doc.select("ul#listul li.cat-item a[href], ul.alphabetical-category-list li a[href]")
            .mapNotNull { a ->
                val text = cleanTitle(a.text())
                if (!text.contains(query, ignoreCase = true)) return@mapNotNull null
                val href = fixUrlNull(a.attr("href").takeIf { it.isNotBlank() }) ?: return@mapNotNull null
                newTvSeriesSearchResponse(text, href, TvType.TvSeries)
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        var currentUrl = url
        var doc = app.get(currentUrl, referer = "$mainUrl/").document

        val parentSeriesHref = doc.selectFirst("div#benzerli a[rel='category tag']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

        if (parentSeriesHref != null && parentSeriesHref != currentUrl) {
            try {
                val seriesDoc = app.get(parentSeriesHref, referer = currentUrl).document
                if (seriesDoc.select("div.bolumust a[href]").isNotEmpty()) {
                    currentUrl = parentSeriesHref
                    doc = seriesDoc
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        val isSeries = currentUrl.contains("/dizi/") ||
            doc.body().hasClass("category") ||
            doc.selectFirst("div#icerikcat") != null ||
            doc.select("div.bolumust a[href]").isNotEmpty()

        return if (isSeries) {
            loadSeries(currentUrl, doc)
        } else {
            loadMovie(currentUrl, doc)
        }
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse? {
        val rawTitle = doc.selectFirst("div.episodecontent div.title h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val title = cleanTitle(rawTitle).takeIf { s -> s.isNotBlank() } ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div.film-afis img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        )

        val plot = doc.selectFirst("div#film-aciklama")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val yearText = doc.selectFirst("div#filmbilgileri div.list-item:contains(Yapım Yılı) a")?.text()
            ?: doc.select("div#listelements a[href*=-filmleri]").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearText.orEmpty())?.value?.toIntOrNull()

        val scoreText = doc.selectFirst("div#listelements div.elements:contains(IMDb)")
            ?.text()
            ?.replace("IMDb:", "", ignoreCase = true)
            ?.trim()

        val tags = doc.select("div#listelements div.elements a[href*=/film-kategori/]")
            .map { it.text().replace(Regex("""\s*Filmleri(\s*izle)?$""", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotBlank() }

        val actors = doc.select("div#filmbilgileri div.list-item:contains(Oyuncular) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val trailer = doc.selectFirst("div#trailer iframe.trailer-video")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

        val recommendations = doc.select("div.singlecontent div.diziler div.listmovie div.movie-box")
            .mapNotNull { it.toMovieSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(scoreText)
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse? {
        val rawTitle = doc.selectFirst("div.incontentx div.title h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("div.episodecontent div.title h1")?.text()
            ?: return null
        val title = cleanTitle(rawTitle).takeIf { s -> s.isNotBlank() } ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div#icerikcatleft div.category_image img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        )

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val yearText = doc.selectFirst("div#icerikcat2 div:contains(Yapım Yılı)")?.text().orEmpty()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val scoreText = doc.selectFirst("div#icerikcat2 div:contains(IMDB)")
            ?.ownText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div#icerikcat2 div:contains(IMDB)")
                ?.text()
                ?.replace(Regex(""".*IMDB\s*:\s*""", RegexOption.IGNORE_CASE), "")
                ?.trim()

        val tags = doc.select("div#icerikcat2 div.genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actorsText = doc.selectFirst("div#icerikcat2 div:contains(Oyuncular)")
            ?.ownText()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div#icerikcat2 div:contains(Oyuncular)")
                ?.text()
                ?.replace(Regex(""".*Oyuncular\s*:\s*""", RegexOption.IGNORE_CASE), "")
                .orEmpty()
        val actors = actorsText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val trailer = doc.selectFirst("div#trailer iframe.trailer-video")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrlNull(it) }

        val seasonEpRegex = Regex("""(\d+)\s*\.\s*Sezon\s*(\d+)\s*\.\s*Bölüm""", RegexOption.IGNORE_CASE)
        val episodes = doc.select("div#scrollbar-container div.container div.bolumust a[href], div.otherepisodes a[href]")
            .mapNotNull { a ->
                val epUrl = fixUrlNull(a.attr("href").takeIf { it.isNotBlank() }) ?: return@mapNotNull null
                val rawLabel = a.selectFirst("div.baslik, div.epidosename")?.text()?.trim() ?: a.text().trim()
                val subTitle = a.selectFirst("div.bolum-ismi")?.text()?.trim()?.removeSurrounding("(", ")").orEmpty()
                val match = seasonEpRegex.find(rawLabel) ?: seasonEpRegex.find(epUrl.replace("-", ". "))
                val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val epNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                val epDisplayName = if (subTitle.isNotBlank()) {
                    "$seasonNum. Sezon ${epNum ?: ""}. Bölüm - $subTitle"
                } else {
                    rawLabel.ifBlank { "Bölüm ${epNum ?: 1}" }
                }

                newEpisode(epUrl) {
                    this.name = epDisplayName
                    this.season = seasonNum
                    this.episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

        val finalEpisodes = episodes.ifEmpty {
            listOf(newEpisode(url) { this.name = title; this.season = 1; this.episode = 1 })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(scoreText)
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainDoc = app.get(data, referer = "$mainUrl/").document
        var foundLinks = false

        val currentTabName = mainDoc.selectFirst("div.diziplus_sources div.sources span.current_dil")
            ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "Kaynak 1"

        val pageTabs = mutableListOf(data to currentTabName)
        mainDoc.select("div.diziplus_sources div.sources a.post-page-numbers[href]").forEach { a ->
            val tabHref = fixUrlNull(a.attr("href").takeIf { it.isNotBlank() }) ?: return@forEach
            val tabName = a.selectFirst("span.dil")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().trim().ifBlank { "Alternatif" }
            if (pageTabs.none { it.first == tabHref }) {
                pageTabs.add(tabHref to tabName)
            }
        }

        for ((tabUrl, tabLabel) in pageTabs) {
            try {
                val doc = if (tabUrl == data) mainDoc else app.get(tabUrl, referer = data).document
                if (extractFromWatchDocument(doc, tabUrl, tabLabel, subtitleCallback, callback)) {
                    foundLinks = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return foundLinks
    }

    private suspend fun extractFromWatchDocument(
        doc: Document,
        pageUrl: String,
        tabLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val embedUrls = mutableSetOf<String>()

        doc.select("div.dizialani iframe[src], div.dizialani iframe[data-src], div#video-player-box iframe[src]")
            .forEach { iframe ->
                val rawSrc = iframe.attr("src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                val fixed = fixUrlNull(rawSrc)
                if (fixed != null && !isIgnoredIframe(fixed)) {
                    embedUrls.add(fixed)
                }
            }

        doc.select(".kaynak-btn[data-embed]").forEach { btn ->
            val dataEmbed = btn.attr("data-embed").takeIf { it.isNotBlank() } ?: return@forEach
            if (dataEmbed.contains("<iframe", ignoreCase = true)) {
                val fragIframe = Jsoup.pars
