package com.anipow

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
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
     * Girişsiz örnek bölüm bağlantılarında görülen biçim:
     * /watch/.../s1e1?source=...
     *
     * Diğer biçimler olabilir. Bu regex yalnızca gözlenen
     * s<sezon>e<bölüm> biçimini ayrıştırır.
     */
    private val episodeNumberRegex =
        Regex("""(?:^|/)s(\d+)e(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

    /*
     * ⚠️ Katalog kartları tarayıcı DOM'unda görüldü.
     * Aynı kartların app.get ile alınan ham HTML'de bulunması
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
     * ⚠️ Gerçek sayfalama doğrulanmadı.
     * Bu denemede yalnızca ilk katalog sayfası kullanılır.
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
     * Alınabilen katalog kartlarında başlık filtresi yapar.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()

        if (term.isEmpty()) {
            return emptyList()
        }

        return catalog().filter { result ->
            result.name.contains(term, ignoreCase = true)
        }
    }

    /*
     * Deneme: Detay sayfasındaki /watch/ bağlantılarından
     * bölüm listesi oluşturur.
     *
     * ⚠️ Bağlantılar tarayıcı DOM'unda görüldü. Ham HTML'de
     * bulunmaları ve bütün sezonları kapsaması doğrulanmadı.
     */
    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("$mainUrl/anime/")) {
            return null
        }

        val document = try {
            app.get(url).document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        /*
         * ⚠️ Poster için h1 başlığına eşit alt metni olan
         * görsel denenir. Bulunamazsa poster boş kalır.
         */
        val poster = document
            .select("img")
            .firstOrNull { image ->
                image.attr("alt").trim().equals(title, ignoreCase = true)
            }
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val episodes: List<Episode> = document
            .select("a[href*='/watch/']")
            .mapNotNull { link ->

                val rawHref = link
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val episodeUrl = fixUrl(rawHref)

                if (!episodeUrl.startsWith("$mainUrl/watch/")) {
                    return@mapNotNull null
                }

                val match = episodeNumberRegex.find(episodeUrl)
                    ?: return@mapNotNull null

                val seasonNumber = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null

                val episodeNumber = match.groupValues[2].toIntOrNull()
                    ?: return@mapNotNull null

                /*
                 * Görülen bölüm kartında ilk span "S1-E1",
                 * ikinci span bölüm adını içeriyordu.
                 *
                 * ⚠️ Diğer sayfalarda yapı farklı olabilir.
                 */
                val episodeName = link
                    .select("span")
                    .getOrNull(1)
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Sezon $seasonNumber Bölüm $episodeNumber"

                newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode>(
                    { it.season ?: Int.MAX_VALUE },
                    { it.episode ?: Int.MAX_VALUE }
                )
            )

        if (episodes.isEmpty()) {
            return null
        }

        /*
         * ⚠️ Film/dizi ayrımı henüz doğrulanmadığı için bütün
         * içerikler bu denemede Anime bölüm listesi olarak açılır.
         * Sub etiketi de geçici bir gösterim tercihidir; gerçek
         * kaynak dilinin doğrulandığı anlamına gelmez.
         */
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = fixUrlNull(poster)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /*
     * Deneme: Bölümün ham HTML'inde [...](...) veya
     * <video>[...](...)</video> bulunursa adresi
     * CloudStream oynatıcısına gönderir.
     *
     * ⚠️ Tarayıcı DOM'unda token'lı video src görüldü; bu src'nin
     * ham HTML'de bulunması, referer gereksinimi ve adresin
     * medya türü doğrulanmadı.
     *
     * Adres yalnızca JavaScript çalıştıktan sonra üretiliyorsa
     * bu yöntem bağlantı bulamaz.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("$mainUrl/watch/")) {
            return false
        }

        val document = try {
            app.get(data).document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }

        val rawSources = document
            .select("video[src], video source[src]")
            .mapNotNull { element ->
                element.attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        var found = false

        for (rawSource in rawSources) {
            val videoUrl = try {
                URI(data).resolve(rawSource).toString()
            } catch (e: Exception) {
                continue
            }

            if (
                !videoUrl.startsWith("https://") &&
                !videoUrl.startsWith("http://")
            ) {
                continue
            }

            /*
             * ⚠️ Referer olarak bölüm sayfası deneniyor;
             * gerekli olup olmadığı doğrulanmadı.
             *
             * Tür belirtilmiyor: CloudStream'in otomatik tür
             * algılaması kullanılıyor. Uzantısız bir kaynakta
             * algılama yanlış olabilir.
             */
            callback(
                newExtractorLink(
                    source = name,
                    name = "AnimPow Video Deneme",
                    url = videoUrl
                ) {
                    referer = data
                }
            )

            found = true
        }

        return found
    }
}
