package com.diziboxizle

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

class DiziBoxIzle : MainAPI() {
    override var mainUrl = "https://diziboxizle.com"
    override var name = "DiziBoxIzle"
    override var lang = "tr"

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ⚠️ DOĞRULANMADI: Bunlar sitenin tüm gerçek sekmeleri değildir.
    // Gereken veri: ana sayfa ve kategori HTML kaynakları.
    override val mainPage = mainPageOf(
        "films" to "Filmler",
        "series" to "Diziler"
    )

    private val api = "$mainUrl/wp-json/wp/v2"
    private val pageSize = 20

    private val episodePattern = Regex(
        """(\d+)\s*\.?\s*sezon\s*(\d+)\s*\.?\s*bölüm""",
        RegexOption.IGNORE_CASE
    )

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun cleanText(value: String): String =
        Jsoup.parse(value).text().trim()

    private fun resolvedUrl(base: String, value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return try {
            val resolved = URI(base).resolve(raw)
            if (resolved.scheme == "http" || resolved.scheme == "https") {
                resolved.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getArray(url: String): JSONArray? {
        return try {
            val response = app.get(url)
            if (!response.isSuccessful) {
                null
            } else {
                JSONArray(response.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private fun JSONArray.objects(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (index in 0 until length()) {
            optJSONObject(index)?.let { result.add(it) }
        }
        return result
    }

    private fun filmTitle(item: JSONObject): String {
        val rendered = item.optJSONObject("title")
            ?.optString("rendered")
            .orEmpty()
        return cleanText(rendered)
    }

    private fun filmPoster(item: JSONObject): String? {
        val media = item.optJSONObject("_embedded")
            ?.optJSONArray("wp:featuredmedia")
            ?.optJSONObject(0)

        return resolvedUrl(mainUrl, media?.optString("source_url"))
    }

    private fun filmResult(item: JSONObject): SearchResponse? {
        val title = filmTitle(item).takeIf { it.isNotBlank() } ?: return null
        val url = resolvedUrl(mainUrl, item.optString("link")) ?: return null
        val poster = filmPoster(item)

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun seriesResult(item: JSONObject): SearchResponse? {
        val title = cleanText(item.optString("name"))
            .takeIf { it.isNotBlank() } ?: return null
        val url = resolvedUrl(mainUrl, item.optString("link")) ?: return null

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page < 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val endpoint = when (request.data) {
            "films" ->
                "$api/film?per_page=$pageSize&page=$page&_embed"

            "series" ->
                "$api/categories?per_page=$pageSize&page=$page&hide_empty=true"

            else ->
                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
        }

        val records = getArray(endpoint)?.objects().orEmpty()
        val items = when (request.data) {
            "films" -> records.mapNotNull { filmResult(it) }
            "series" -> records.mapNotNull { seriesResult(it) }
            else -> emptyList()
        }

        // ⚠️ DOĞRULANMADI: Son sayfa tam doluysa bir boş sayfa daha
        // istenebilir. Kesin kontrol için X-WP-TotalPages başlığı gerekir.
        return newHomePageResponse(
            request.name,
            items,
            hasNext = records.size == pageSize
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()

        val q = encode(text)

        // ⚠️ DOĞRULANMADI: REST araması sitenin HTML aramasıyla eşdeğer
        // olmayabilir. Türkçe sorguda boş sonuç gözlendi.
        val films = getArray(
            "$api/film?search=$q&per_page=100&_embed"
        )?.objects().orEmpty().mapNotNull { filmResult(it) }

        val series = getArray(
            "$api/categories?search=$q&per_page=100&hide_empty=true"
        )?.objects().orEmpty().mapNotNull { seriesResult(it) }

        return films + series
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            loadPage(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun loadPage(url: String): LoadResponse? {
        val pageUrl = resolvedUrl(mainUrl, url) ?: return null
        val pageUri = URI(pageUrl)

        // Film için iki işaret: /film/ yolu ve WordPress film kaydı.
        val slug = pageUri.path.trim('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: return null

        if (pageUri.path.startsWith("/film/")) {
            val film = getArray(
                "$api/film?slug=${encode(slug)}&per_page=1&_embed"
            )?.optJSONObject(0) ?: return null

            val title = filmTitle(film).takeIf { it.isNotBlank() }
                ?: return null
            val poster = filmPoster(film)

            // ⚠️ DOĞRULANMADI: Genel Open Graph seçicisi gerçek film
            // HTML'ine karşı test edilmedi.
            val doc = app.get(pageUrl).document
            val plot = doc.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.Movie,
                pageUrl
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Dizi için iki işaret: aynı slug'a sahip kategori ve o kategoriye
        // bağlı bölüm kayıtları.
        val category = getArray(
            "$api/categories?slug=${encode(slug)}&per_page=1"
        )?.optJSONObject(0) ?: return null

        val categoryId = category.optInt("id", 0)
        val categoryUrl = resolvedUrl(mainUrl, category.optString("link"))
        val categoryTitle = cleanText(category.optString("name"))

        if (categoryId <= 0 || categoryUrl == null || categoryTitle.isBlank()) {
            return null
        }

        val episodes = mutableListOf<Episode>()
        var episodePage = 1

        while (true) {
            val rows = getArray(
                "$api/posts?categories=$categoryId" +
                    "&per_page=100&page=$episodePage"
            ) ?: return null

            val records = rows.objects()

            for (item in records) {
                val episodeUrl = resolvedUrl(
                    mainUrl,
                    item.optString("link")
                ) ?: continue

                val title = filmTitle(item)
                if (title.isBlank()) continue

                val match = episodePattern.find(title)
                val seasonNumber = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episodeNumber = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = title
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                )
            }

            if (records.size < 100) break
            episodePage += 1
        }

        if (episodes.isEmpty()) return null

        val sortedEpisodes = episodes.sortedWith(
            compareBy<Episode>(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE }
            )
        )

        // ⚠️ DOĞRULANMADI: Open Graph alanlarının bu sayfada bulunması
        // ham HTML ile doğrulanmadı.
        val doc = app.get(categoryUrl).document
        val poster = resolvedUrl(
            categoryUrl,
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return newTvSeriesLoadResponse(
            categoryTitle,
            categoryUrl,
            TvType.TvSeries,
            sortedEpisodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = resolvedUrl(mainUrl, data) ?: return false

        val doc = try {
            app.get(pageUrl, referer = mainUrl).document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            return false
        }

        val candidates = linkedSetOf<String>()

        // ⚠️ DOĞRULANMADI: Bunlar genel HTML yedekleridir, siteye ait
        // player seçicileri değildir. Gereken veri: gerçek player DOM'u.
        val elements = doc.select(
            "iframe[src], iframe[data-src], " +
                "video[src], video[data-src], " +
                "source[src], source[data-src], " +
                "[data-video], [data-embed]"
        )

        for (element in elements) {
            for (attribute in listOf("src", "data-src", "data-video", "data-embed")) {
                resolvedUrl(pageUrl, element.attr(attribute))
                    ?.let { candidates.add(it) }
            }
        }

        // ⚠️ DOĞRULANMADI: Düz metindeki doğrudan medya URL'lerini arar;
        // packed JS, Base64 ve AJAX player zincirlerini çözmez.
        val mediaPattern = Regex(
            """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        for (match in mediaPattern.findAll(doc.html())) {
            candidates.add(match.value)
        }

        var emitted = false

        for (candidate in candidates) {
            try {
                val path = URI(candidate).path.orEmpty().lowercase()

                when {
                    path.endsWith(".m3u8") -> {
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                candidate,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = pageUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        emitted = true
                    }

                    path.endsWith(".mp4") -> {
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                candidate,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        emitted = true
                    }

                    else -> {
                        loadExtractor(
                            candidate,
                            pageUrl,
                            subtitleCallback
                        ) { link ->
                            emitted = true
                            callback(link)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        return emitted
    }
}

@CloudstreamPlugin
class DiziBoxIzlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziBoxIzle())
    }
}
