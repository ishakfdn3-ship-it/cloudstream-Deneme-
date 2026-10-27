 // ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.nl"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler" to "Altyazılı Bölümler",
        "${mainUrl}/dublaj-bolumler" to "Dublaj Bölümler",
        "${mainUrl}/dizi-turu/aile" to "Aile",
        "${mainUrl}/dizi-turu/aksiyon" to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik" to "Romantik",
        "${mainUrl}/dizi-turu/komedi" to "Komedi"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("dizi-turu")) {
            document.select("div.grid-cols-3 a").mapNotNull { it.diziler() }
        } else {
            document.select("div.grid a").mapNotNull { it.sonBolumler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name = selectFirst("h2")?.text() ?: return null
        val epName = selectFirst("div.opacity-80")?.text()
            ?.replace(". Sezon ", "x")
            ?.replace(". Bölüm", "") ?: return null
        val title = "$name - $epName"

        val epDoc = app.get(attr("href")).document
        val href = fixUrlNull(epDoc.selectFirst("a.relative")?.attr("href"))
            ?: return null
        val posterUrl = fixUrlNull(
            epDoc.selectFirst("img.imgt")?.attr("onerror")
                ?.substringAfter("= '")
                ?.substringBefore("';")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        return newTvSeriesSearchResponse(
            title ?: return null,
            "${mainUrl}/${slug}",
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainReq = app.get(mainUrl)
        val mainPage = mainReq.document
        val cKey = mainPage.selectFirst("input[name='cKey']")?.attr("value")
            ?: return emptyList()
        val cValue = mainPage.selectFirst("input[name='cValue']")?.attr("value")
            ?: return emptyList()

        val veriler = mutableListOf<SearchResponse>()

        val searchReq = app.post(
            "${mainUrl}/bg/searchcontent",
            data = mapOf(
                "cKey" to cKey,
                "cValue" to cValue,
                "searchterm" to query
            ),
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "${mainUrl}/",
            cookies = mapOf(
                "showAllDaFull" to "true",
                "PHPSESSID" to mainReq.cookies["PHPSESSID"].toString()
            )
        ).parsedSafe<SearchResult>()

        if (searchReq?.data?.state != true) {
            throw ErrorLoadingException("Invalid Json response")
        }

        searchReq.data.result?.forEach { searchItem ->
            veriler.add(searchItem.toSearchResponse() ?: return@forEach)
        }

        return veriler
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.page-top h1")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("data-src"))
        val year = document.selectXpath(
            "//span[text()='Yayın tarihi']//following-sibling::span"
        ).text().trim().split(" ").last().toIntOrNull()
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim()
            ?: document.selectFirst("div.w-full div.text-base")?.text()?.trim()
        val tags = document.select("[href*='dizi-turu']").map { it.text() }
        val duration = Regex("(\\d+)")
            .find(document.select("div.gap-3 span.text-sm").getOrNull(1)?.text().orEmpty())
            ?.value?.toIntOrNull()
        val actors = document.select("[href*='oyuncu']").map {
            Actor(it.text())
        }

        val episodeList = mutableListOf<Episode>()

        document.selectXpath("//div[contains(@class, 'gap-2')]/a[contains(@href, '-sezon')]")
            .forEach { seasonLink ->
                val epDoc = app.get(
                    fixUrlNull(seasonLink.attr("href")) ?: return@forEach
                ).document

                epDoc.select("div.episodes div.cursor-pointer").forEach ep@ { episodeElement ->
                    val epName = episodeElement.select("a").last()?.text()?.trim()
                        ?: return@ep
                    val epHref = fixUrlNull(
                        episodeElement.selectFirst("a.opacity-60")?.attr("href")
                    ) ?: return@ep
                    val epDescription = episodeElement.selectFirst("span.t-content")
                        ?.text()?.trim()
                    val epPoster = epDoc.selectFirst("img.object-cover")?.attr("src")
                    val epEpisode = episodeElement.selectFirst("a.opacity-60")
                        ?.text()?.toIntOrNull()

                    val parentDiv = episodeElement.parent()
                    val seasonClass = parentDiv?.className()?.split(" ")
                        ?.find { it.startsWith("szn") }
                    val epSeason = seasonClass?.substringAfter("szn")?.toIntOrNull()

                    episodeList.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                        this.description = epDescription
                        this.posterUrl = epPoster
                    })
                }

                epDoc.select("div.dub-episodes div.cursor-pointer")
                    .forEach epDub@ { episodeElement ->
                        val epName = episodeElement.select("a").last()?.text()?.trim()
                            ?: return@epDub
                        val epHref = fixUrlNull(
                            episodeElement.selectFirst("a.opacity-60")?.attr("href")
                        ) ?: return@epDub
                        val epDescription = episodeElement.selectFirst("span.t-content")
                            ?.text()?.trim()
                        val epPoster = epDoc.selectFirst("img.object-cover")?.attr("src")
                        val epEpisode = episodeElement.selectFirst("a.opacity-60")
                            ?.text()?.toIntOrNull()

                        val parentDiv = episodeElement.parent()
                        val seasonClass = parentDiv?.className()?.split(" ")
                            ?.find { it.startsWith("szn") }
                        val epSeason = seasonClass?.substringAfter("szn")?.toIntOrNull()

                        episodeList.add(newEpisode(epHref) {
                            this.name = "$epName Dublaj"
                            this.season = epSeason
                            this.episode = epEpisode
                            this.description = epDescription
                            this.posterUrl = epPoster
                        })
                    }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.duration = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZL", "data » $data")
        val document = app.get(data).document
        val iframes = mutableSetOf<String>()

        val alternatifler = document.select("a[href*='player']")

        if (alternatifler.isEmpty()) {
            val iframe = fixUrlNull(
                document.selectFirst("div#playerLsDizilla iframe")?.attr("src")
            ) ?: return false

            Log.d("DZL", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        } else {
            for (alternative in alternatifler) {
                val playerUrl = fixUrlNull(alternative.attr("href"))
                    ?: continue
                val playerDoc = app.get(playerUrl).document
                val iframe = fixUrlNull(
                    playerDoc.selectFirst("div#playerLsDizilla iframe")?.attr("src")
                ) ?: continue

                if (!iframes.add(iframe)) continue

                Log.d("DZL", "iframe » $iframe")
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
