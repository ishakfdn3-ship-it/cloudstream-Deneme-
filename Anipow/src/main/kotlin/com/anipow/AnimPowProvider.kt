package com.animpow

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException

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
     * ⚠️ Katalog kartları tarayıcıda görüldü.
     * CloudStream'in aldığı ham HTML'de aynı kartların bulunup
     * bulunmadığı doğrulanmadı. Site kartları JavaScript ile
     * oluşturuyorsa sonuç boş gelebilir.
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

                // Yalnızca AnimPow ayrıntı bağlantılarını kabul et.
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
     * ⚠️ Sayfalama doğrulanmadı.
     * Bu nedenle yalnızca ilk katalog sayfası denenir.
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
     * ⚠️ Bu, sitenin gerçek arama endpoint'i değildir.
     * Alınabilen katalog kartlarını başlığa göre filtreler.
     * Bu nedenle eksik sonuç verebilir.
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
     * ⚠️ Film/dizi detayları ve bölüm listesi doğrulanmadı.
     * Şu anda detay ekranı açılmaz.
     */
    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    /*
     * ⚠️ Video adresinin nasıl üretildiği ve gerekli başlıklar
     * doğrulanmadı. Şu anda video bağlantısı döndürülmez.
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
