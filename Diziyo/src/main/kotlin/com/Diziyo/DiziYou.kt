package com.diziyou

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder


// CloudStream uygulaması açıldığında sağlayıcıyı kaydeder.
@CloudstreamPlugin
class DiziYouPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziYou())
    }
}


class DiziYou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // Ana sayfa bölümlerini tek tek alıyoruz.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa"
    )

    /*
     * Bir görselin adresini alır.
     * Bazı sayfalarda görsel src, bazılarında data-src içindedir.
     */
    private fun imageUrl(element: Element?): String? {
        if (element == null) return null

        val lazy = element.attr("data-src").trim()
        val normal = element.attr("src").trim()

        val url = if (lazy.isNotEmpty()) lazy else normal
        return url.takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
    }

    /*
     * Ana sayfadaki bir dizi kartını CloudStream sonucuna çevirir.
     */
    private fun homeCardToResult(card: Element): SearchResponse? {
        val link = card.selectFirst("a[href]") ?: return null
        val href = link.attr("href").trim()
        if (href.isEmpty()) return null

        val image = link.selectFirst("img") ?: card.selectFirst("img")

        val title = sequenceOf(
            card.selectFirst("div.cat-title-main span")?.text(),
            card.selectFirst("div.cat-title-main")?.text(),
            image?.attr("alt")
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        val poster = imageUrl(image)

        return newTvSeriesSearchResponse(
            title,
            fixUrl(href),
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    /*
     * Ana sayfadaki kategori başlıklarını ve dizi kartlarını okur.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val document = app.get(request.data).document
        val sections = arrayListOf<HomePageList>()

        for (heading in document.select("div.title")) {
            val sectionName = heading.text()
                .replace(
                    Regex("Tümünü Gör.*", RegexOption.IGNORE_CASE),
                    ""
                )
                .trim()

            if (sectionName.isEmpty()) continue

            val block = heading.nextElementSibling() ?: continue
            val cards = block.select("div#list-series-main")

            val results = cards.mapNotNull { card ->
                homeCardToResult(card)
            }.distinctBy { it.url }

            if (results.isNotEmpty()) {
                sections.add(HomePageList(sectionName, results))
            }
        }

        return newHomePageResponse(sections)
    }

    /*
     * Arama sonucundaki bir kartı okur.
     */
    private fun searchCardToResult(card: Element): SearchResponse? {
        val link = card.selectFirst("div#categorytitle a[href]")
            ?: return null

        val title = link.text().trim()
        val href = link.attr("href").trim()

        if (title.isEmpty() || href.isEmpty()) return null

        val poster = imageUrl(card.selectFirst("img"))

        return newTvSeriesSearchResponse(
            title,
            fixUrl(href),
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    /*
     * Örnek: "Dark Matter" aramasını siteye gönderir.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document

        return document
            .select("div.incontent div#list-series")
            .mapNotNull { searchCardToResult(it) }
            .distinctBy { it.url }
    }

    /*
     * Dizi sayfasını açar:
     * isim, afiş, açıklama, türler ve bölümler.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = imageUrl(
            document.selectFirst("div.category_image img")
        )

        val description = document.selectFirst("div.diziyou_desc")
            ?.text()
            ?.trim()

        val tags = document.select("div.genres a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        val seasonPattern = Regex(
            """(\d+)\.\s*Sezon""",
            RegexOption.IGNORE_CASE
        )

        val episodePattern = Regex(
            """(\d+)\.\s*Bölüm""",
            RegexOption.IGNORE_CASE
        )

        val episodes = document.select("div.bolumust").mapNotNull { row ->
            val heading = row.selectFirst("div.baslik")
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val rawLink = row.closest("a[href]")
                ?.attr("href")
                ?.trim()
                ?: return@mapNotNull null

            if (rawLink.isEmpty()) return@mapNotNull null

            val seasonNumber = seasonPattern.find(heading)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: 1

            val episodeNumber = episodePattern.find(heading)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

            val episodeName = row.selectFirst("div.bolumismi")
                ?.text()
                ?.trim()
                ?.trim('(', ')')
                ?.takeIf { it.isNotEmpty() }
                ?: heading

            newEpisode(fixUrl(rawLink)) {
                this.name = episodeName
                this.season = seasonNumber
                this.episode = episodeNumber
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    /*
     * Bölüm sayfasından player kimliğini ve dil seçeneklerini alır.
     * Sonra oynatma ve altyazı bağlantılarını CloudStream'e gönderir.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val playerUrl = document
            .selectFirst("iframe#diziyouPlayer")
            ?.attr("src")
            ?.trim()
            ?: return false

        val itemId = playerUrl
            .substringBefore("?")
            .substringAfterLast("/")
            .removeSuffix(".html")
            .trim()

        if (itemId.isEmpty()) return false

        // Bu adres düzeni incelenen DiziYou eklentisinden alınmıştır.
        val storage = "https://storage.diziyou.one"

        val optionIds = document
            .select("span.diziyouOption")
            .map { it.id() }
            .toSet()

        var foundStream = false

        // newExtractorLink suspend olduğu için bu fonksiyon da suspend olmalı.
        suspend fun sendStream(label: String, streamUrl: String) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = INFER_TYPE
                ) {
                    headers = mapOf("Referer" to "$mainUrl/")
                    quality = Qualities.Unknown.value
                }
            )

            foundStream = true
        }

        val hasOriginal =
            "turkceAltyazili" in optionIds ||
            "ingilizceAltyazili" in optionIds

        if (hasOriginal) {
            sendStream(
                "Orijinal Dil",
                "$storage/episodes/$itemId/play.m3u8"
            )
        }

        if ("turkceAltyazili" in optionIds) {
            subtitleCallback.invoke(
                SubtitleFile(
                    "Turkish",
                    "$storage/subtitles/$itemId/tr.vtt"
                )
            )
        }

        if ("ingilizceAltyazili" in optionIds) {
            subtitleCallback.invoke(
                SubtitleFile(
                    "English",
                    "$storage/subtitles/$itemId/en.vtt"
                )
            )
        }

        if ("turkceDublaj" in optionIds) {
            sendStream(
                "Türkçe Dublaj",
                "$storage/episodes/${itemId}_tr/play.m3u8"
            )
        }

        return foundStream
    }
}
