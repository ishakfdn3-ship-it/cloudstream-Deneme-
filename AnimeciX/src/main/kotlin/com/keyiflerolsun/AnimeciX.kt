// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class AnimeciX : MainAPI() {

    override var mainUrl = "https://animecix.tv"
    override var name = "AnimeciX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val apiHeaders = mapOf(
        "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "$mainUrl/secure/titles?type=movie&onlyStreamable=true" to "Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(
            "${request.data}&page=$page&perPage=16",
            headers = apiHeaders
        ).parsedSafe<Category>()

        val home = response?.pagination?.data?.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "$mainUrl/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder
            .encode(query, "UTF-8")
            .replace("+", "%20")

        val response = app.get(
            "$mainUrl/secure/search/$encodedQuery?limit=20"
        ).parsedSafe<Search>() ?: return emptyList()

        return response.results.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "$mainUrl/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    private fun packSources(urls: List<String>): String {
        val array = JSONArray()

        urls
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { array.put(it) }

        return "acx_sources:$array"
    }

    private fun unpackSources(data: String): List<String> {
        if (!data.startsWith("acx_sources:")) {
            // Eski tek kaynaklı kayıtlarla uyumluluk.
            return listOf(data.trim()).filter { it.isNotBlank() }
        }

        val json = data.removePrefix("acx_sources:")
        val array = JSONArray(json)

        return (0 until array.length())
            .mapNotNull { index ->
                array.optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = apiHeaders
        ).parsedSafe<Title>() ?: run {
            Log.e("ACX", "İçerik bilgileri okunamadı")
            return null
        }

        val episodes = mutableListOf<Episode>()
        val titleId = response.title.id

        if (response.title.titleType == "anime") {
            for (season in response.title.seasons) {
                val seasonResponse = app.get(
                    "$mainUrl/secure/related-videos" +
                        "?episode=1" +
                        "&season=${season.number}" +
                        "&videoId=0" +
                        "&titleId=$titleId"
                ).parsedSafe<TitleVideos>()

                if (seasonResponse == null) {
                    Log.e(
                        "ACX",
                        "Sezon bilgileri okunamadı: ${season.number}"
                    )
                    continue
                }

                /*
                 * Bölüm numarası olan kayıtları sezon + bölüm
                 * numarasına göre grupla.
                 *
                 * Bölüm numarası olmayan kayıtları ayrı tut:
                 * ilgisiz videoları yanlışlıkla birleştirmeyelim.
                 */
                val groupedVideos = seasonResponse.videos
                    .withIndex()
                    .groupBy { indexedVideo ->
                        val video = indexedVideo.value

                        if (video.episodeNum == null) {
                            "unknown-${indexedVideo.index}"
                        } else {
                            val seasonNumber =
                                video.seasonNum ?: season.number

                            "$seasonNumber-${video.episodeNum}"
                        }
                    }

                for ((_, indexedVideos) in groupedVideos) {
                    val videos = indexedVideos.map { it.value }
                    val firstVideo = videos.firstOrNull() ?: continue

                    val sourceUrls = videos
                        .map { it.url }
                        .filter { it.isNotBlank() }
                        .distinct()

                    if (sourceUrls.isEmpty()) {
                        continue
                    }

                    val seasonNumber =
                        firstVideo.seasonNum ?: season.number

                    val episodeNumber = firstVideo.episodeNum
                    val episodeData = packSources(sourceUrls)

                    episodes.add(
                        newEpisode(episodeData) {
                            this.name = if (episodeNumber != null) {
                                "$seasonNumber. Sezon $episodeNumber. Bölüm"
                            } else {
                                "$seasonNumber. Sezon Bölüm"
                            }

                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    )

                    Log.d(
                        "ACX",
                        "Sezon $seasonNumber, bölüm $episodeNumber: " +
                            "${sourceUrls.size} kaynak"
                    )
                }
            }
        } else {
            val sourceUrls = response.title.videos
                .map { it.url }
                .filter { it.isNotBlank() }
                .distinct()

            if (sourceUrls.isEmpty()) {
                Log.e("ACX", "Film için video kaydı bulunamadı")
            } else {
                val episodeData = packSources(sourceUrls)

                episodes.add(
                    newEpisode(episodeData) {
                        this.name = "Filmi İzle"
                        this.season = 1
                        this.episode = 1
                    }
                )

                Log.d(
                    "ACX",
                    "Film için ${sourceUrls.size} kaynak bulundu"
                )
            }
        }

        return newTvSeriesLoadResponse(
            response.title.title,
            "$mainUrl/secure/titles/$titleId?titleId=$titleId",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year = response.title.year
            this.plot = response.title.description
            this.tags = response.title.tags.map { it.name }

            addActors(
                response.title.actors.map {
                    Actor(it.name, fixUrlNull(it.poster))
                }
            )

            addTrailer(response.title.trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceUrls = try {
            unpackSources(data)
        } catch (e: Exception) {
            Log.e("ACX", "Kaynak listesi okunamadı", e)
            return false
        }

        if (sourceUrls.isEmpty()) {
            Log.e("ACX", "Kaynak listesi boş")
            return false
        }

        Log.d("ACX", "İşlenecek kaynak sayısı: ${sourceUrls.size}")

        val refererUrl = "$mainUrl/"
        var foundAny = false

        for ((index, source) in sourceUrls.withIndex()) {
            try {
                val sourceUrl = URI(refererUrl)
                    .resolve(source)
                    .toString()

                val sourcePath = URI(sourceUrl).path.orEmpty()

                // Doğrudan medya adreslerini önce indirmeye çalışma.
                val finalUrl = if (
                    sourcePath.endsWith(".m3u8", ignoreCase = true) ||
                    sourcePath.endsWith(".mp4", ignoreCase = true)
                ) {
                    sourceUrl
                } else {
                    val response = app.get(
                        sourceUrl,
                        referer = refererUrl
                    )

                    Log.d(
                        "ACX",
                        "Kaynak ${index + 1} HTTP: ${response.code}"
                    )

                    if (response.code !in 200..299) {
                        Log.e(
                            "ACX",
                            "Kaynak ${index + 1} isteği başarısız"
                        )
                        continue
                    }

                    response.url
                }

                val finalPath = URI(finalUrl).path.orEmpty()

                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    foundAny = true
                    callback(link)
                }

                if (
                    finalPath.endsWith(".m3u8", ignoreCase = true) ||
                    finalPath.endsWith(".mp4", ignoreCase = true)
                ) {
                    wrappedCallback(
                        newExtractorLink(
                            source = name,
                            name = "$name Kaynak ${index + 1}",
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = refererUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    /*
                     * Kayıtlı extractor'ları kullanır.
                     * AnimeciXPlugin.kt içindeki TauVideo kaydı da
                     * bu aşamada kullanılabilir.
                     */
                    loadExtractor(
                        finalUrl,
                        refererUrl,
                        subtitleCallback,
                        wrappedCallback
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "ACX",
                    "Kaynak ${index + 1} işlenemedi",
                    e
                )
            }
        }

        Log.d("ACX", "En az bir bağlantı bulundu: $foundAny")
        return foundAny
    }
}
