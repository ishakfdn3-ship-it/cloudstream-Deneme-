package com.ishak.filmmodu

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

class FilmModu : MainAPI() {

    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"

    override val hasMainPage = true

    // ⚠️ DOĞRULANMADI: Chromecast ve indirme davranışı test edilmedi.
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ✅ Ana sayfa doğrudan görüntülendi.
    // ⚠️ Diğer iki URL arama sonuçlarında görüldü; sayfalama test edilmedi.
    override val mainPage = mainPageOf(
        mainUrl to "Filmler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/filmizle/4k-filmler-izle" to "4K Filmler"
    )

    private suspend fun getDocument(
        url: String,
        referer: String = mainUrl
    ): Document? {
        return try {
            val html = app.get(url, referer = referer).text
            Jsoup.parse(html, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private fun resolveUrl(base: String, value: String?): String? {
        val raw = value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: return null

        return try {
            URI(base).resolve(raw).toString().takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSiteContentUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()

            host.equals(URI(mainUrl).host, ignoreCase = true) &&
                (path.startsWith("/film/") || path.startsWith("/dizi/"))
        } catch (_: Exception) {
            false
        }
    }

    private fun isSeriesUrl(url: String): Boolean {
        return try {
            URI(url).path.orEmpty().startsWith("/dizi/")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ⚠️ DOĞRULANMADI:
     * Kart CSS sınıfları elimizde yok. Genel bağlantı taraması kullanılır.
     * Bu nedenle bazı kartlar kaçabilir veya ilgisiz bağlantılar gelebilir.
     */
    private fun parseCards(
        document: Document,
        baseUrl: String
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (anchor in document.select("a[href]")) {
            val url = resolveUrl(baseUrl, anchor.attr("href")) ?: continue
            if (!isSiteContentUrl(url)) continue
            if (!seen.add(url)) continue

            val image = anchor.selectFirst("img")

            val title = listOf(
                anchor.selectFirst("h1, h2, h3, h4")?.text().orEmpty(),
                anchor.attr("title"),
                image?.attr("alt").orEmpty(),
                anchor.text()
            ).firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: continue

            val poster = image?.let {
                listOf(
                    it.attr("data-src"),
                    it.attr("data-lazy-src"),
                    it.attr("src")
                ).firstOrNull { value ->
                    value.isNotBlank() && !value.startsWith("data:")
                }
            }.let { resolveUrl(baseUrl, it) }

            val result = if (isSeriesUrl(url)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            }

            results.add(result)
        }

        return results
    }

    /**
     * ⚠️ DOĞRULANMADI:
     * Yalnızca sayfada açıkça "next" veya "sonraki" bağlantısı varsa
     * sayfalama yapar. Site farklı bir yapı kullanıyorsa durur.
     */
    private fun findNextPage(
        document: Document,
        currentUrl: String
    ): String? {
        val relNext = document.selectFirst(
            "a[rel=next][href], link[rel=next][href]"
        )

        if (relNext != null) {
            return resolveUrl(currentUrl, relNext.attr("href"))
        }

        val nextAnchor = document.select("a[href]").firstOrNull { anchor ->
            val text = anchor.text().trim().lowercase()
            val label = anchor.attr("aria-label").trim().lowercase()

            text == "sonraki" ||
                text == "next" ||
                label.contains("sonraki") ||
                label == "next"
        }

        return nextAnchor?.let {
            resolveUrl(currentUrl, it.attr("href"))
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page < 1) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        var currentUrl = request.data
        var document = getDocument(currentUrl)
            ?: return newHomePageResponse(
                request,
                emptyList(),
                hasNext = false
            )

        val visited = mutableSetOf(currentUrl)

        for (index in 1 until page) {
            val next = findNextPage(document, currentUrl)
                ?: return newHomePageResponse(
                    request,
                    emptyList(),
                    hasNext = false
                )

            if (!visited.add(next)) {
                return newHomePageResponse(
                    request,
                    emptyList(),
                    hasNext = false
                )
            }

            currentUrl = next
            document = getDocument(currentUrl)
                ?: return newHomePageResponse(
                    request,
                    emptyList(),
                    hasNext = false
                )
        }

        val results = parseCards(document, currentUrl)
        val next = findNextPage(document, currentUrl)

        return newHomePageResponse(
            request,
            results,
            hasNext = next != null && next !in visited
        )
    }

    /**
     * ⚠️ DOĞRULANMADI:
     * Gerçek arama endpoint'i bilinmediğinden bu yalnızca seçili
     * liste sayfalarındaki başlıklar arasında yerel filtreleme yapar.
     * Sitenin tüm arşivinde arama yapmaz.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val pages = listOf(
            mainUrl,
            "$mainUrl/populer-filmler",
            "$mainUrl/filmizle/4k-filmler-izle"
        )

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (pageUrl in pages) {
            val document = getDocument(pageUrl) ?: continue

            for (item in parseCards(document, pageUrl)) {
                if (
                    item.name.contains(needle, ignoreCase = true) &&
                    seen.add(item.url)
                ) {
                    results.add(item)
                }
            }
        }

        return results
    }

    private fun getMovieJson(document: Document): JSONObject? {
        // ✅ Gönderilen film kaynağının 35. satırında Movie JSON-LD var.
        // ⚠️ Diğer filmlerde ve ham fixture üzerinde henüz test edilmedi.
        for (script in document.select("script[type=application/ld+json]")) {
            val obj = try {
                JSONObject(script.data())
            } catch (_: Exception) {
                continue
            }

            if (obj.optString("@type") == "Movie") {
                return obj
            }
        }

        return null
    }

    private fun getGenres(movie: JSONObject): List<String>? {
        val array = movie.optJSONArray("genre") ?: return null
        val genres = mutableListOf<String>()

        for (index in 0 until array.length()) {
            val genre = array.optString(index)
                .takeIf { it.isNotBlank() }
                ?: continue

            genres.add(genre)
        }

        return genres.takeIf { it.isNotEmpty() }
    }

    private fun extractSeasonEpisode(label: String): Pair<Int, Int>? {
        // ✅ Gönderilen dizi metninde "1. Sezon 1. Bölüm" biçimi görülüyor.
        // ⚠️ Bölüm bağlantılarının HTML yapısı henüz doğrulanmadı.
        val match = Regex(
            """(\d+)\s*\.\s*Sezon\s*(\d+)\s*\.\s*Bölüm""",
            RegexOption.IGNORE_CASE
        ).find(label) ?: return null

        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null

        return season to episode
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url) ?: return null

        if (isSeriesUrl(url)) {
            val title = document.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val poster = resolveUrl(
                url,
                document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
            )

            val plot = document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")?.takeIf { it.isNotBlank() }

            val episodes = mutableListOf<Episode>()
            val seen = mutableSetOf<String>()

            // ⚠️ DOĞRULANMADI:
            // Bölüm etiketleri bağlantı içinde değilse sonuç boş kalır.
            for (anchor in document.select("a[href]")) {
                val label = anchor.text().trim()
                val numbers = extractSeasonEpisode(label) ?: continue
                val episodeUrl = resolveUrl(url, anchor.attr("href"))
                    ?: continue

                if (!seen.add(episodeUrl)) continue

                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = label
                        this.season = numbers.first
                        this.episode = numbers.second
                    }
                )
            }

            // Diziyi yanlışlıkla film olarak sunma.
            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val movie = getMovieJson(document) ?: return null

        val title = movie.optString("name")
            .takeIf { it.isNotBlank() }
            ?: return null

        val poster = resolveUrl(
            url,
            movie.optString("image").takeIf { it.isNotBlank() }
        )

        val description = movie.optString("description")
            .takeIf { it.isNotBlank() }

        val year = movie.optString("dateCreated")
            .take(4)
            .toIntOrNull()

        val genres = getGenres(movie)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = getDocument(data) ?: return false

        val candidates = mutableListOf(data)

        // ⚠️ DOĞRULANMADI:
        // FilmModu player'ı iframe yerine AJAX ile açıyorsa bu yedek bulamaz.
        for (frame in page.select("iframe[src], iframe[data-src]")) {
            val raw = frame.attr("src")
                .ifBlank { frame.attr("data-src") }

            val embed = resolveUrl(data, raw) ?: continue
            candidates.add(embed)
        }

        var emitted = false

        for (candidate in candidates.distinct()) {
            try {
                val html = if (candidate == data) {
                    page.outerHtml()
                } else {
                    app.get(candidate, referer = data).text
                }

                // ✅ Gönderilen Vidmixi embed örneği üzerinde AES çözümü
                // ayrı bir yerel testte başarılı oldu.
                // ⚠️ Bu Kotlin uygulaması henüz derlenmedi.
                val decoded = VidmixiDecoder.decode(html)

                if (decoded != null) {
                    callback(
                        newExtractorLink(
                            "Vidmixi",
                            "Vidmixi",
                            decoded.videoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = candidate
                            this.quality = Qualities.Unknown.value
                        }
                    )

                    emitted = true

                    // ⚠️ DOĞRULANMADI:
                    // Göreli altyazı yolları embed URL'sine göre çözülüyor;
                    // gerçek altyazı isteği test edilmedi.
                    for (track in decoded.subtitles) {
                        val subtitleUrl = resolveUrl(candidate, track.file)
                            ?: continue

                        val label = track.label.ifBlank {
                            track.language.ifBlank { "Altyazı" }
                        }

                        val subtitle = newSubtitleFile(label, subtitleUrl) {
                            this.headers = mapOf("Referer" to candidate)
                        }

                        subtitleCallback(subtitle)
                    }
                } else if (candidate != data) {
                    // ⚠️ Genel yedek. Host desteklenmiyorsa sonuç vermez.
                    loadExtractor(
                        candidate,
                        data,
                        subtitleCallback
                    ) { link ->
                        emitted = true
                        callback(link)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }

        // Bu, oynatmanın başarılı olduğunu değil, link üretildiğini gösterir.
        return emitted
    }
}

private data class VidmixiSubtitle(
    val file: String,
    val label: String,
    val language: String
)

private data class VidmixiData(
    val videoUrl: String,
    val subtitles: List<VidmixiSubtitle>
)

private object VidmixiDecoder {

    // ✅ Kullanıcının verdiği embed örneğinde bePlayer(hash, set) var.
    // ⚠️ Diğer sözdizimleri ve farklı örnekler doğrulanmadı.
    private val playerCall = Regex(
        """bePlayer\s*\(\s*'([^']+)'\s*,\s*'((?:\\.|[^'\\])*)'"""
    )

    fun decode(html: String): VidmixiData? {
        val match = playerCall.find(html) ?: return null

        val password = match.groupValues[1]
        val encryptedJson = match.groupValues[2]

        return try {
            val payload = JSONObject(encryptedJson)
            val salt = hexToBytes(payload.getString("s")) ?: return null

            val ciphertext = Base64.decode(
                payload.getString("ct"),
                Base64.DEFAULT
            )

            val keyAndIv = evpBytesToKey(
                password.toByteArray(Charsets.UTF_8),
                salt,
                48
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES"),
                IvParameterSpec(keyAndIv.copyOfRange(32, 48))
            )

            val plaintext = cipher.doFinal(ciphertext)
                .toString(Charsets.UTF_8)

            val settings = JSONObject(plaintext)

            val videoUrl = settings.optString("video_location")
                .takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                }
                ?: return null

            val subtitles = mutableListOf<VidmixiSubtitle>()
            val tracks = settings.optJSONArray("strSubtitles")

            if (tracks != null) {
                for (index in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(index) ?: continue

                    if (track.optString("kind") != "captions") continue

                    val file = track.optString("file")
                        .takeIf { it.isNotBlank() }
                        ?: continue

                    subtitles.add(
                        VidmixiSubtitle(
                            file = file,
                            label = track.optString("label"),
                            language = track.optString("language")
                        )
                    )
                }
            }

            VidmixiData(videoUrl, subtitles)
        } catch (_: Exception) {
            null
        }
    }

    private fun evpBytesToKey(
        password: ByteArray,
        salt: ByteArray,
        length: Int
    ): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        val output = ArrayList<Byte>(length)
        var previous = ByteArray(0)

        while (output.size < length) {
            md5.reset()
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()

            previous.forEach { output.add(it) }
        }

        return output.take(length).toByteArray()
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (value.length % 2 != 0) return null

        return try {
            ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2)
                    .toInt(16)
                    .toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

@CloudstreamPlugin
class FilmModuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmModu())
    }
}
