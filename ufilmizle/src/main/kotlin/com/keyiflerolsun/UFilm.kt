package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

class UFilm : MainAPI() {

    override var mainUrl = "https://ufilmizle.com"
    override var name = "UFilm"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler"
    )

    /**
     * Sayfadaki film kartlarını okur.
     *
     * Sitede dizi ve filmler aynı listede bulunabiliyor.
     * İçerik tipi detay sayfasında bölüm listesine göre belirlenir.
     */
    private fun parseResults(doc: Document): List<SearchResponse> {
        val foundUrls = mutableSetOf<String>()

        return doc.select("div.img > a[href]:has(img)")
            .mapNotNull { link ->
                val rawUrl = link.attr("href").trim()
                val url = fixUrlNull(rawUrl) ?: return@mapNotNull null

                if (!url.contains("/filmler9/")) {
                    return@mapNotNull null
                }

                if (!foundUrls.add(url)) {
                    return@mapNotNull null
                }

                val image = link.selectFirst("img")
                    ?: return@mapNotNull null

                val title = image.attr("alt").trim()
                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val poster = image.attr("data-src")
                    .ifBlank { image.attr("src") }
                    .let { fixUrlNull(it) }

                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "$mainUrl/page/$page/"
        }

        return try {
            val response = app.get(url)

            Log.d("UFILM", "Ana sayfa $page HTTP: ${response.code}")

            val results = parseResults(response.document)

            Log.d("UFILM", "Ana sayfa $page: ${results.size} içerik")

            newHomePageResponse(request.name, results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UFILM", "Ana sayfa okunamadı", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val response = app.get("$mainUrl/?s=$encodedQuery")

            Log.d("UFILM", "Arama HTTP: ${response.code}")

            val results = parseResults(response.document)

            Log.d("UFILM", "Arama sonucu: ${results.size} içerik")

            results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UFILM", "Arama başarısız", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(url)
            val doc = response.document

            Log.d("UFILM", "Detay sayfası HTTP: ${response.code}")

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val poster = doc
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.let { fixUrlNull(it) }

            val plot = doc
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(title)
                ?.value
                ?.toIntOrNull()

            // Örnek dizi sayfasında aktif bölüm bir span,
            // diğer bölümler post-page-numbers bağlantılarıydı.
            val currentLabel = doc
                .selectFirst(".filmplus_sources .current_dil")
                ?.text()
                ?.trim()
                .orEmpty()

            val episodeLinks = doc.select(
                ".filmplus_sources a.post-page-numbers[href]"
            )

            val episodePattern = Regex(
                """(\d+)\s*[.\-]?\s*bölüm""",
                RegexOption.IGNORE_CASE
            )

            val hasEpisodeLabels =
                episodePattern.containsMatchIn(currentLabel) ||
                    episodeLinks.any {
                        episodePattern.containsMatchIn(it.text())
                    }

            if (hasEpisodeLabels) {
                val episodes = mutableListOf<Episode>()
                val seenUrls = mutableSetOf<String>()

                // Site farklı sezonları farklı içerik sayfalarında
                // sunabilir. Sayfa başlığında sezon yoksa 1 kullanılır.
                val season = Regex(
                    """(\d+)\s*[.\-]?\s*sezon""",
                    RegexOption.IGNORE_CASE
                ).find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                fun addEpisode(
                    episodeUrl: String,
                    label: String
                ) {
                    val fixedUrl = fixUrlNull(episodeUrl) ?: return
                    if (!seenUrls.add(fixedUrl)) return

                    val episodeNumber = episodePattern.find(label)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()

                    episodes.add(
                        newEpisode(fixedUrl) {
                            this.name = label.ifBlank {
                                if (episodeNumber != null) {
                                    "$episodeNumber. Bölüm"
                                } else {
                                    "Bölüm"
                                }
                            }

                            this.season = season
                            this.episode = episodeNumber
                        }
                    )
                }

                // Aktif bölümün adresi mevcut sayfanın kendisidir.
                addEpisode(url, currentLabel)

                episodeLinks.forEach { link ->
                    addEpisode(
                        link.attr("href"),
                        link.text().trim()
                    )
                }

                Log.d("UFILM", "Dizi bölümleri: ${episodes.size}")

                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            } else {
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    url
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UFILM", "İçerik bilgileri okunamadı", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(data)
            val doc = response.document

            Log.d("UFILM", "Video sayfası HTTP: ${response.code}")

            if (response.code !in 200..299) {
                return false
            }

            val sources = doc.select(
                ".video-container iframe, " +
                    ".filmalani iframe, " +
                    ".video-container source"
            ).mapNotNull { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .trim()

                if (raw.isBlank()) {
                    null
                } else {
                    URI(data).resolve(raw).toString()
                }
            }.distinct()

            Log.d("UFILM", "Bulunan oynatıcı sayısı: ${sources.size}")

            var found = false

            val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                found = true
                callback(link)
            }

            for (source in sources) {
                try {
                    val path = URI(source).path.orEmpty()

                    if (
                        path.endsWith(".m3u8", ignoreCase = true) ||
                        path.endsWith(".mp4", ignoreCase = true)
                    ) {
                        wrappedCallback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = source,
                                type = INFER_TYPE
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        loadExtractor(
                            source,
                            data,
                            subtitleCallback,
                            wrappedCallback
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("UFILM", "Oynatıcı çözülemedi", e)
                }
            }

            Log.d("UFILM", "Video bağlantısı bulundu: $found")
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UFILM", "Video sayfası okunamadı", e)
            false
        }
    }
}
