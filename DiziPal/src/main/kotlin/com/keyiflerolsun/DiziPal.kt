
 // ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal950.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

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
        val document = app.get(request.data).document

        val home = if (request.data.contains("/diziler/son-bolumler")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("article.type2 ul li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name = selectFirst("div.name")?.text() ?: return null
        val episode = selectFirst("div.episode")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title = "$name $episode"
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(
            title,
            href.substringBefore("/sezon"),
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("span.title")?.text() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
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
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/",
            data = mapOf("query" to query)
        )

        val searchItemsMap =
            jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)

        return searchItemsMap.values.map { it.toPostSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
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

        val document = app.get(data).document
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
