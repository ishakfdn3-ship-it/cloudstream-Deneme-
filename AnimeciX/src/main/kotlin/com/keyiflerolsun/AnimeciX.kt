// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
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

                for (video in seasonResponse.videos) {
                    episodes.add(
                        newEpisode(video.url) {
                            this.name =
                                "${video.seasonNum}. Sezon " +
                                "${video.episodeNum}. Bölüm"
                            this.season = video.seasonNum
                            this.episode = video.episodeNum
                        }
                    )
                }
            }
        } else {
            val video = response.title.videos.firstOrNull()

            if (video != null) {
                episodes.add(
                    newEpisode(video.url) {
                        this.name = "Filmi İzle"
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                Log.e("ACX", "Film için video kaydı bulunamadı")
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
        if (data.isBlank()) {
            Log.e("ACX", "Video adresi boş")
            return false
        }

        val refererUrl = "$mainUrl/"
        var found = false

        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        try {
            val videoUrl = URI(refererUrl)
                .resolve(data.trim())
                .toString()

            val path = URI(videoUrl).path.orEmpty()

            // Doğrudan medya adresini indirmeden oynatıcıya gönder.
            if (
                path.endsWith(".m3u8", ignoreCase = true) ||
                path.endsWith(".mp4", ignoreCase = true)
            ) {
                wrappedCallback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Önce kayıtlı extractor'ları doğrudan dene.
            try {
                loadExtractor(
                    videoUrl,
                    refererUrl,
                    subtitleCallback,
                    wrappedCallback
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ACX", "İlk extractor denemesi başarısız", e)
            }

            if (found) {
                Log.d("ACX", "İlk adreste bağlantı bulundu")
                return true
            }

            // Extractor sonuç vermediyse yönlendirmeyi takip et.
            val response = app.get(
                videoUrl,
                referer = refererUrl
            )

            Log.d("ACX", "Video yanıt kodu: ${response.code}")

            if (response.code !in 200..299) {
                Log.e("ACX", "Video isteği başarısız")
                return false
            }

            val finalUrl = response.url
            val finalPath = URI(finalUrl).path.orEmpty()

            if (
                finalPath.endsWith(".m3u8", ignoreCase = true) ||
                finalPath.endsWith(".mp4", ignoreCase = true)
            ) {
                wrappedCallback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else if (finalUrl != videoUrl) {
                loadExtractor(
                    finalUrl,
                    refererUrl,
                    subtitleCallback,
                    wrappedCallback
                )
            }

            Log.d("ACX", "Bağlantı bulundu: $found")
            return found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ACX", "Video bağlantısı çıkarılamadı", e)
            return false
        }
    }
}
