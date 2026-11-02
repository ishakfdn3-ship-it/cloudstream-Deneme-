package com.anipow

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.CancellationException

class AnimPowProvider : MainAPI() {

    override var name = "AnimPow Deneme"

    override var mainUrl = "https://animpow.com"

    override var lang = "tr"

    override val supportedTypes = setOf(TvType.Anime)

    override val hasMainPage = true

    override val hasQuickSearch = false

    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "$mainUrl/catalog" to "Anime Kataloğu"
    )

    /*
     * ⚠️ Kartlar tarayıcı DOM'unda görüldü.
     * CloudStream'in aldığı ham HTML'de de bulunup bulunmadıkları
     * henüz doğrulanmadı.
     */
    private suspend fun catalog(): List<SearchResponse> {
        val document = try {
            app.get("$mainUrl/catalog").document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }

        return document
            .select("a[href*='/anime/']")
            .mapNotNull { card ->

                val title = card
                    .selectFirst(".anime-card-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val rawHref = card
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val url = fixUrl(rawHref)

                if (!url.startsWith("$mainUrl/anime/")) {
                    return@mapNotNull null
                }

                val poster = card
                    .selectFirst("img.anime-card-poster")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                newAnimeSearchResponse(title, url) {
                    posterUrl = fixUrlNull(poster)
                }
            }
            .distinctBy { it.url }
    }

    /*
     * ⚠️ Katalog sayfalaması doğrulanmadı.
     * Yalnızca ilk sayfa denenir.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = if (page == 1) {
            catalog()
        } else {
            emptyList()
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    /*
     * ⚠️ Bu gerçek site araması değil.
     * Alınabilen katalog kartlarını başlığa göre filtreler.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()

        if (term.isEmpty()) {
            return emptyList()
        }

        return catalog().filter {
            it.name.contains(term, ignoreCase = true)
        }
    }

    /*
     * ⚠️ Detay ve bölüm listesi henüz desteklenmiyor.
     */
    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    /*
     * ⚠️ Video kaynak zinciri henüz doğrulanmadı.
     * Bu sürüm video oynatmaz.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
