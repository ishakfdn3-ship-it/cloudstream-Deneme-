package com.filmmodu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmModuProvider : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Sitenin gerçek kategori ve liste URL yapıları
    override val mainPage = mainPageOf(
        "$mainUrl/hd-film-izle" to "Son Eklenen Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/imdb-7-uzeri-filmler" to "IMDb 7+ Filmler",
        "$mainUrl/boxset-filmler" to "Seri Filmler"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private suspend fun getDocument(url: String): Document =
        app.get(url, headers = requestHeaders).document

    private fun parseYear(value: String?): Int? =
        Regex("(19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()

    private fun parseScore(value: String?): Score? =
        Regex("\\d+(\\.\\d+)?").find(value.orEmpty()?.replace(',', '.') ?: "")
            ?.value?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }?.let { Score.from10(it) }

    private fun elementToSearch(element: Element): SearchResponse? {
        // Element doğrudan <a> etiketiyse kendisini, değilse içindeki ilk anlamlı <a> etiketini al
        val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href]") ?: return null
        val href = linkEl.absUrl("href").takeIf { it.isNotBlank() && it.startsWith(mainUrl) } ?: return null

        // Kategori, iletişim, giriş gibi film olmayan linkleri filtrele
        if (href.contains("/kategori/") || href.contains("/tur/") || href.contains("/iletisim") || href.contains("/uye-") || href == "$mainUrl/") {
            return null
        }

        val imgEl = element.selectFirst("img")
        val title = element.selectFirst(".title, .film-name, .name, h2, h3, .poster-title")?.text()?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: linkEl.attr("title").trim()
            .ifBlank { linkEl.text().trim() }

        if (title.isBlank()) return null

        val posterRaw = imgEl?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("src")
                }
            }
        }
        val poster = posterRaw?.let { fixUrlNull(it) }

        val yearText = element.selectFirst(".year, .film-year, .date, span.year")?.text()
        val scoreText = element.selectFirst(".imdb, .rating, .score, .imdb-rating")?.text()

        val isSeries = href.contains("/dizi/") || href.contains("-sezon-") || href.contains("-bolum-")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = parseYear(yearText)
                this.score = parseScore(scoreText)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = parseYear(yearText)
                this.score = parseScore(scoreText)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val baseUrl = request.data
        val url = if (page <= 1) {
            baseUrl
        } else {
            // Sitenin sayfalama yapısına göre (?page=2 veya /page/2)
            if (baseUrl == mainUrl) "$mainUrl/?page=$page" else "$baseUrl?page=$page"
        }

        val document = runCatching { getDocument(url) }.getOrNull() ?: return null

        // FilmModu ve benzeri temaların kullandığı tüm olası film kutusu seçicileri
        var cards = document.select("div.movie, div.poster, div.film-box, article.movie, .movie-list .item, .film-list .item, .posters > div, .row .col-md-2, .row .col-xs-6")

        // Eğer yukarıdaki seçiciler boş dönerse, içlerinde resim ve başlık barındıran tüm film linklerini yakala
        if (cards.isEmpty()) {
            cards = document.select("a[href*='/film/'], a[href*='-izle']")
        }

        val items = cards.mapNotNull { elementToSearch(it) }.distinctBy { it.url }

        // Eğer ilk sekmede hala veri bulunamadıysa ana sayfayı doğrudan yedek olarak tara
        if (items.isEmpty() && page == 1 && baseUrl != mainUrl) {
            val homeDoc = runCatching { getDocument(mainUrl) }.getOrNull()
            val fallbackItems = homeDoc?.select("div.poster, div.movie, a[href*='/film/'], a[href*='-izle']")
                ?.mapNotNull { elementToSearch(it) }
                ?.distinctBy { it.url } ?: emptyList()
            return newHomePageResponse(request.name, fallbackItems, hasNext = fallbackItems.isNotEmpty())
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // FilmModu arama endpoint alternatifleri
        val searchUrls = listOf(
            "$mainUrl/film-ara?q=$encoded",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for (searchUrl in searchUrls) {
            val document = runCatching { getDocument(searchUrl) }.getOrNull() ?: continue
            val cards = document.select("div.movie, div.poster, div.film-box, article, a[href*='/film/'], a[href*='-izle']")
            val results = cards.mapNotNull { elementToSearch(it) }.distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)

        val title = document.selectFirst("h1, .titles h1, .film-title")?.text()?.trim()
            ?: document.title().substringBefore(" izle").substringBefore(" -").trim()
            .ifBlank { return null }

        val posterRaw = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster img, .film-poster img")?.attr("src")
        val poster = fixUrlNull(posterRaw)

        val plot = document.selectFirst(".description, .summary, .film-summary, p[itemprop=description], meta[ property=og:description], meta[name=description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()

        val year = parseYear(document.selectFirst(".year, .release-year, a[href*='/yil/']")?.text())
        val score = parseScore(document.selectFirst(".imdb, .imdb-rating, .score")?.text())
        val tags = document.select("a[href*='/tur/'], a[href*='/kategori/'], .genres a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val recommendations = document.select("div.poster, div.movie, .similar-movies a, a[href*='/film/']")
            .mapNotNull { elementToSearch(it) }
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            this.tags = tags.takeIf { it.isNotEmpty() }
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        val foundEmbeds = mutableSetOf<String>()

        // 1. Sayfadaki doğrudan iframe'leri topla (YouTube fragmanlarını atla)
        document.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.absUrl("data-src") }
            if (src.isNotBlank() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                foundEmbeds.add(src)
            }
        }

        // 2. Data attribute'larında (Altyazılı / Dublaj butonlarında) yer alan linkleri topla
        document.select("[data-src], [data-url], [data-frame], [data-embed]").forEach { el ->
            listOf("data-src", "data-url", "data-frame", "data-embed").forEach { attr ->
                val link = el.absUrl(attr)
                if (link.isNotBlank() && !link.contains("youtube.com") && !link.endsWith(".jpg") && !link.endsWith(".png") && !link.endsWith(".webp")) {
                    foundEmbeds.add(link)
                }
            }
        }

        // 3. Script içerisindeki vidmixi veya m3u8 bağlantılarını yakala
        val scripts = document.select("script").html()
        Regex("""["'](https?://(?:[^"']*vidmixi\.com|[^"']*/embed/|[^"']*\.m3u8)[^"']*)["']""").findAll(scripts).forEach { match ->
            foundEmbeds.add(match.groupValues[1].replace("\\/", "/"))
        }

        // 4. Klasik FilmModu /get-source AJAX yapısı varsa (data-video-id ile tetiklenen)
        document.select("[data-video-id], [data-id]").forEach { btn ->
            val videoId = btn.attr("data-video-id").ifBlank { btn.attr("data-id") }
            val type = btn.attr("data-type").ifBlank { "tr" }
            if (videoId.isNotBlank() && videoId.all { it.isDigit() }) {
                runCatching {
                    val ajaxText = app.get(
                        "$mainUrl/get-source?movie_id=$videoId&type=$type",
                        referer = data,
                        headers = requestHeaders + ("X-Requested-With" to "XMLHttpRequest")
                    ).text
                    Regex("""["'](https?://[^"']+)["']""").findAll(ajaxText).forEach { m ->
                        val extracted = m.groupValues[1].replace("\\/", "/")
                        if (!extracted.contains("youtube.com")) foundEmbeds.add(extracted)
                    }
                }
            }
        }

        if (foundEmbeds.isEmpty()) return false

        for (embedUrl in foundEmbeds) {
            if (embedUrl.contains("vidmixi.com")) {
                extractVidmixi(embedUrl, data, subtitleCallback, callback)
            } else if (embedUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = embedUrl,
                    referer = data,
                    headers = requestHeaders
                ).forEach(callback)
            } else {
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractVidmixi(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val embedHtml = app.get(embedUrl, referer = refererUrl, headers = requestHeaders).text

            // Görsellerde yakaladığımız /m3u/ veya .m3u8 uzantılı Vidmixi akışlarını yakala
            val streamRegex = Regex("""["']((?:https?://vidmixi\.com)?/m3u8?/[^"']+|https?://[^"']+\.m3u8[^"']*)["']""")
            val rawStream = streamRegex.find(embedHtml)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)

            if (!rawStream.isNullOrBlank()) {
                val cleanPath = rawStream.replace("\\/", "/")
                val finalStreamUrl = if (cleanPath.startsWith("http")) cleanPath else "https://vidmixi.com$cleanPath"

                val streamHeaders = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://vidmixi.com",
                    "Referer" to embedUrl,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Vidmixi",
                        url = finalStreamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // VTT altyazılarını da (subtitle_tur.vtt, subtitle_eng.vtt) yakala
            Regex("""["']((?:https?://[^"']+)?/[^"']*subtitle_([a-z]{2,3})\.vtt[^"']*)["']""").findAll(embedHtml).forEach { subMatch ->
                val subPath = subMatch.groupValues[1].replace("\\/", "/")
                val langCode = subMatch.groupValues[2]
                val fullSubUrl = if (subPath.startsWith("http")) subPath else "https://vidmixi.com$subPath"
                val langName = when (langCode) {
                    "tur", "tr" -> "Türkçe"
                    "eng", "en" -> "İngilizce"
                    else -> langCode.uppercase()
                }
                subtitleCallback.invoke(SubtitleFile(langName, fullSubUrl))
            }
        }
    }
}
