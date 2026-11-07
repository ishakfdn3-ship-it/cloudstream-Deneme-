package com.kullanici.yabancidiziizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
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
        "$mainUrl/film-kategori/korku/" to "Korku Filmleri"
    )

    private fun trimTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("""\s*-\s*Yabanc[ıi]diziizle\.in.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Archives$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Tüm\s+Bölümler(\s+izle)?.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun posterOf(img: Element?): String? {
        if (img == null) return null
        val src = listOf("data-src", "data-lazy-src", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith("lazy.png", true) }
        return fixUrlNull(src)
    }

    private fun iframeSrcOf(iframe: Element): String? {
        val raw = listOf("data-src", "data-lazy-src", "src")
            .map { iframe.attr(it).trim() }
            .firstOrNull {
                it.isNotBlank() &&
                    !it.equals("about:blank", ignoreCase = true) &&
                    !it.startsWith("data:") &&
                    !it.startsWith("javascript:")
            } ?: return null
        val fixed = fixUrlNull(raw) ?: return null
        val lower = fixed.lowercase()
        if (lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("youtube.com") || lower.contains("google.com")) {
            return null
        }
        return fixed
    }

    private fun Element.toCardResult(): SearchResponse? {
        val a = selectFirst("div.film-ismi a[href], div.serie-name a[href], h3 a[href], a.cmfp-poster[href], div.poster a[href], a[href]")
            ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href.isBlank()) return null

        val rawName = selectFirst("div.film-ismi a, div.serie-name a, h3 a")?.text()
            ?: selectFirst("img")?.attr("alt")?.ifBlank { selectFirst("img")?.attr("title") }
            ?: a.text()
        val cleanName = trimTitle(rawName)
        if (cleanName.isBlank()) return null

        val epSuffix = selectFirst("div.list-episodes div.episode-name a, div.episode-name a")?.text()?.trim().orEmpty()
        val finalTitle = if (epSuffix.isNotBlank() && !epSuffix.matches(Regex("""\d{4}"""))) {
            "$cleanName - $epSuffix"
        } else {
            cleanName
        }

        val poster = posterOf(selectFirst("img"))
        val scoreStr = selectFirst("div.film-ust, div.episode-date")?.text()
            ?.replace("IMDb:", "", ignoreCase = true)?.trim()

        val isSeries = href.contains("/dizi/") || hasClass("episode-box")
        return if (isSeries) {
            newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(scoreStr)
            }
        } else {
            newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.score = Score.from10(scoreStr)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.substringBefore("#")
        val frag = request.data.substringAfter("#", "")
        val url = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document

        val items: List<SearchResponse> = when (frag) {
            "diziler" -> if (page > 1) emptyList() else {
                val boxes = doc.select("div.diziler div.list-series div.episode-box").mapNotNull { it.toCardResult() }
                val listItems = doc.select("ul#listul li.cat-item a[href]").mapNotNull { a ->
                    val h = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val t = trimTitle(a.text())
                    if (t.isBlank()) null else newTvSeriesSearchResponse(t, h, TvType.TvSeries)
                }
                (boxes + listItems).distinctBy { it.url }
            }
            "one-cikanlar" -> if (page > 1) emptyList() else {
                doc.select("section.cmfp-recommended article.cmfp-card, section.cmfp-upcoming article.cmfp-card")
                    .mapNotNull { it.toCardResult() }
                    .distinctBy { it.url }
            }
            else -> doc.select("div.listmovie div.movie-box, div.list-series div.episode-box, article.cmfp-card")
                .mapNotNull { it.toCardResult() }
                .distinctBy { it.url }
        }

        val hasNext = frag.isEmpty() && items.isNotEmpty() &&
            doc.selectFirst("link[rel=next], a.next, a.nextpostslink") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", referer = "$mainUrl/").document
        val cards = doc.select("div.listmovie div.movie-box, div.list-series div.episode-box, article.cmfp-card, div.list-episodes div.episode-box")
            .mapNotNull { it.toCardResult() }
            .distinctBy { it.url }
        if (cards.isNotEmpty()) return cards

        return doc.select("ul#listul li.cat-item a[href]").mapNotNull { a ->
            val t = trimTitle(a.text())
            val h = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (t.contains(query, ignoreCase = true)) newTvSeriesSearchResponse(t, h, TvType.TvSeries) else null
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        var targetUrl = url
        var doc = app.get(targetUrl, referer = "$mainUrl/").document

        val parentHref = fixUrlNull(doc.selectFirst("div#benzerli a[rel='category tag']")?.attr("href"))
        if (!parentHref.isNullOrBlank() && parentHref != targetUrl) {
            try {
                val sDoc = app.get(parentHref, referer = targetUrl).document
                if (sDoc.select("div.bolumust a[href]").isNotEmpty()) {
                    targetUrl = parentHref
                    doc = sDoc
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        val rawTitle = doc.selectFirst("div.episodecontent div.title h1, div.incontentx div.title h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
        val title = trimTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            doc.selectFirst("div.film-afis img, div#icerikcatleft div.category_image img")?.attr("src")?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val plot = doc.selectFirst("div#film-aciklama")?.ownText()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()

        val yearStr = doc.select("div#filmbilgileri div.list-item:contains(Yapım Yılı), div#icerikcat2 div:contains(Yapım Yılı)").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearStr)?.value?.toIntOrNull()

        val scoreStr = doc.select("div#listelements div.elements:contains(IMDb), div#icerikcat2 div:contains(IMDB)").text()
            .replace(Regex(""".*IMD[bB]\s*:\s*"""), "").trim()

        val tags = doc.select("div#listelements div.elements a[href*=/film-kategori/], div#icerikcat2 div.genres a")
            .map { it.text().replace(Regex("""\s*Filmleri(\s*izle)?$""", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotBlank() }

        val actors = (
            doc.select("div#filmbilgileri div.list-item:contains(Oyuncular) a").map { it.text().trim() } +
                doc.selectFirst("div#icerikcat2 div:contains(Oyuncular)")?.ownText().orEmpty().split(",").map { it.trim() }
            ).filter { it.isNotBlank() }.distinct()

        val trailer = fixUrlNull(doc.selectFirst("div#trailer iframe.trailer-video")?.attr("src"))

        val isSeries = targetUrl.contains("/dizi/") || doc.selectFirst("div#icerikcat") != null || doc.select("div.bolumust a[href]").isNotEmpty()
        if (isSeries) {
            val epRegex = Regex("""(\d+)\s*\.\s*Sezon\s*(\d+)\s*\.\s*Bölüm""", RegexOption.IGNORE_CASE)
            val episodes = doc.select("div.bolumust a[href], div.otherepisodes a[href]").mapNotNull { a ->
                val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val label = a.selectFirst("div.baslik, div.epidosename")?.text()?.trim() ?: a.text().trim()
                val match = epRegex.find(label) ?: epRegex.find(epUrl.replace("-", ". "))
                val sNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                newEpisode(epUrl) {
                    this.name = label.ifBlank { "Bölüm ${eNum ?: 1}" }
                    this.season = sNum
                    this.episode = eNum
                }
            }.distinctBy { it.data }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

            return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(scoreStr)
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val recs = doc.select("div.singlecontent div.diziler div.listmovie div.movie-box").mapNotNull { it.toCardResult() }
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(scoreStr)
                this.tags = tags
                this.recommendations = recs
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
        val mainDoc = app.get(data, referer = "$mainUrl/").document
        var emittedCount = 0

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            emittedCount++
            callback(link)
        }

        val curTab = mainDoc.selectFirst("div.diziplus_sources div.sources span.current_dil")?.text()?.trim().orEmpty().ifBlank { "Kaynak 1" }
        val pages = mutableListOf(data to curTab)
        mainDoc.select("div.diziplus_sources div.sources a.post-page-numbers[href]").forEach { a ->
            val h = fixUrlNull(a.attr("href"))
            val t = a.text().trim().ifBlank { "Alternatif" }
            if (!h.isNullOrBlank() && pages.none { it.first == h }) {
                pages.add(h to t)
            }
        }

        for ((pageUrl, tabName) in pages) {
            try {
                val doc = if (pageUrl == data) mainDoc else app.get(pageUrl, referer = data).document
                val embeds = mutableSetOf<String>()

                doc.select("div.dizialani iframe, div.video-container iframe, div#video-player-box iframe, iframe")
                    .mapNotNull { iframeSrcOf(it) }
                    .forEach { embeds.add(it) }

                doc.select("[data-embed]").forEach { el ->
                    val rawEmbed = el.attr("data-embed")
                    Regex("""src=["']([^"']+)["']""").find(rawEmbed)?.groupValues?.getOrNull(1)?.let { s ->
                        fixUrlNull(s)?.let { embeds.add(it) }
                    }
                }

                for (embed in embeds) {
                    val beforeCount = emittedCount
                    try {
                        loadExtractor(embed, pageUrl, subtitleCallback, wrappedCallback)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logError(e)
                    }

                    // Eğer loadExtractor (veya kayıtlı Extractor) hiçbir link üretmediyse doğrudan yedek tarayıcıyı çalıştır
                    if (emittedCount == beforeCount) {
                        try {
                            val cleanEmbed = embed.substringBefore("?").takeIf { embed.contains("dzen.ru") } ?: embed
                            val text = app.get(
                                cleanEmbed,
                                referer = pageUrl,
                                headers = mapOf("User-Agent" to StreamHelper.MOBILE_UA)
                            ).text
                            val streams = StreamHelper.extractStreamUrls(text)
                            for (stream in streams) {
                                wrappedCallback(
                                    newExtractorLink("$name ($tabName)", "$name ($tabName)", stream, ExtractorLinkType.M3U8) {
                                        this.referer = cleanEmbed
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "User-Agent" to StreamHelper.MOBILE_UA,
                                            "Referer" to cleanEmbed
                                        )
                                    }
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
        return emittedCount > 0
    }
}
// SON
