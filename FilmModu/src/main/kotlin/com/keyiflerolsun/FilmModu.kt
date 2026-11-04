package com.example.filmmodu

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilmModu : MainAPI() {

    override var mainUrl = "https://filmmodu.cc"
    override var name = "FilmModu"
    override var lang = "tr"

    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmizle/aksiyon-filmleri-izle" to "Aksiyon",
        "$mainUrl/filmizle/komedi-filmleri-hd-izle" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri-izle" to "Korku",
        "$mainUrl/filmizle/dram-filmleri-izle" to "Dram",
        "$mainUrl/filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu",
        "$mainUrl/filmizle/animasyon-filmleri-izle" to "Animasyon",
        "$mainUrl/filmizle/turkce-dublaj-filmler-hd-izle" to
            "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-hd-izle" to
            "Türkçe Altyazılı",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler"
    )

    /**
     * Sayfa parametresi tahmindir. Sitenin mevcut sayfalaması
     * doğrulanmadan üretim sürümü olarak kabul edilmemelidir.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(pageUrl).document
        val results = parseCards(document)

        val hasNext = document.selectFirst(
            "a[rel=next], .pagination a[rel=next]"
        ) != null

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNext
        )
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val movieCards = document.select("article.movie_box a.image")

        val seriesCards = document.select(
            "main a[href*='/dizi/']"
        ).filter { element ->
            val href = element.attr("href")
            !href.contains("/sezon-") &&
                !href.contains("/bolum-")
        }

        return (movieCards + seriesCards)
            .mapNotNull { card -> card.toSearchItem() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val href = attr("abs:href")
            .ifBlank { resolve(mainUrl, attr("href")) }
            ?: return null

        val title = selectFirst("h2, h3, .title, strong")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: attr("title")
                .removeSuffix(" izle")
                .trim()
                .takeIf { it.isNotEmpty() }
            ?: return null

        val image = selectFirst("img")
        val poster = image?.let {
            val raw = it.attr("data-src")
                .ifBlank { it.attr("src") }

            if (raw.startsWith("data:")) {
                null
            } else {
                resolve(href, raw)
            }
        }

        val year = selectFirst(".year")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val scoreText = selectFirst(".imdb")
            ?.text()
            ?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
                this.year = year
                score = Score.from10(scoreText)
            }
        } else {
            newMovieSearchResponse(
                name = title,
                url = href,
                type = TvType.Movie
            ) {
                posterUrl = poster
                this.year = year
                score = Score.from10(scoreText)
            }
        }
    }

    /**
     * /arama/oneri adresi sayfada görüldü.
     * q parametresi arama alanının isminden çıkarılmış bir tahmindir.
     * Yanıt JSON veya HTML ise ikisini de okumayı dener.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "$mainUrl/arama/oneri?q=$encoded",
            referer = "$mainUrl/"
        )

        val body = response.text

        val htmlResults = parseCards(
            Jsoup.parse(body, mainUrl)
        )

        if (htmlResults.isNotEmpty()) return htmlResults

        return parseSearchJson(body)
    }

    private fun parseSearchJson(body: String): List<SearchResponse> {
        val root = try {
            when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                body.trimStart().startsWith("{") -> JSONObject(body)
                else -> return emptyList()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val objects = mutableListOf<JSONObject>()

        fun collect(value: Any?) {
            when (value) {
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        collect(value.opt(i))
                    }
                }

                is JSONObject -> {
                    objects.add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        collect(value.opt(keys.next()))
                    }
                }
            }
        }

        collect(root)

        return objects.mapNotNull { item ->
            val title = firstText(
                item,
                "title", "name", "baslik", "ad"
            ) ?: return@mapNotNull null

            val rawUrl = firstText(
                item,
                "url", "link", "href"
            ) ?: return@mapNotNull null

            val url = resolve(mainUrl, rawUrl)
                ?: return@mapNotNull null

            if (!url.contains("/film/") &&
                !url.contains("/dizi/")
            ) {
                return@mapNotNull null
            }

            val poster = firstText(
                item,
                "poster", "image", "img"
            )?.let { resolve(mainUrl, it) }

            if (url.contains("/dizi/")) {
                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            } else {
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val metadata = findStructuredMetadata(document)

        val title = metadata?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("main h1")
                ?.text()
                ?.trim()
            ?: return null

        val poster = metadata?.optString("image")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")

        val description = metadata?.optString("description")
            ?.takeIf { it.isNotBlank() }

        val year = metadata?.optString("dateCreated")
            ?.take(4)
            ?.toIntOrNull()
            ?: document.selectFirst("a[href*='/yil/']")
                ?.text()
                ?.trim()
                ?.take(4)
                ?.toIntOrNull()

        val rating = metadata
            ?.optJSONObject("aggregateRating")
            ?.optString("ratingValue")
            ?.takeIf { it.isNotBlank() }

        val genres = jsonStrings(metadata?.opt("genre"))

        val actors = metadata
            ?.optJSONArray("actor")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)
                        ?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Actor(it) }
                }
            }
            ?: emptyList()

        if (url.contains("/dizi/")) {
            val episodes = document.select(
                "section.dz-bolumler a.dz-bolum"
            ).mapNotNull { element ->
                val episodeUrl = element.attr("abs:href")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val match = Regex(
                    """/sezon-(\d+)/bolum-(\d+)"""
                ).find(episodeUrl)
                    ?: return@mapNotNull null

                val seasonNumber =
                    match.groupValues[1].toIntOrNull()

                val episodeNumber =
                    match.groupValues[2].toIntOrNull()

                newEpisode(episodeUrl) {
                    name = element.attr("title")
                        .takeIf { it.isNotBlank() }

                    season = seasonNumber
                    episode = episodeNumber
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = description
                this.year = year
                score = Score.from10(rating)
                tags = genres
                addActors(actors)
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            score = Score.from10(rating)
            tags = genres
            addActors(actors)
        }
    }

    /**
     * En iyi çaba:
     * 1. Sayfada mevcut iframe varsa CloudStream extractor'a ilet.
     * 2. iframe HTML'inde açık bir HLS adresi varsa oynatıcıya ilet.
     * 3. bePlayer("hash", "set", ...) çağrısı düz metin olarak
     *    bulunursa CryptoJS ayarlarını çözmeyi dene.
     *
     * Filmmodu'nun dinamik iframe oluşturma isteği henüz bilinmediği
     * için bu yöntem tüm içeriklerde çalışmak zorunda değildir.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            foundLink = true
            callback(link)
        }

        try {
            val page = app.get(data)
            val document = page.document

            val frames = document.select(
                "#f_player iframe[src], #fm_cerceve iframe[src]"
            )

            for (frame in frames) {
                val iframeUrl = resolve(
                    data,
                    frame.attr("src")
                ) ?: continue

                try {
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                        countingCallback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Diğer yöntemleri dene.
                }

                val iframeHtml = try {
                    app.get(
                        iframeUrl,
                        referer = data
                    ).text
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }

                val config = findPlayerConfig(iframeHtml)

                val hlsUrl = config
                    ?.optString("video_location")
                    ?.takeIf { it.isNotBlank() }
                    ?: findPlainHlsUrl(iframeHtml)

                if (!hlsUrl.isNullOrBlank()) {
                    val absoluteHls = resolve(iframeUrl, hlsUrl)
                        ?: continue

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HLS",
                            url = absoluteHls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = iframeUrl
                        }
                    )

                    foundLink = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return foundLink
        }

        return foundLink
    }

    /**
     * Yalnızca bağımsız bir bePlayer çağrısı düz metinde mevcutsa
     * ve iki argüman basit JavaScript string'leriyse çalışır.
     * İsimlendirilmiş değişkenler veya farklı JS kodlamaları için
     * bu ayrıştırıcı yeterli değildir.
     */
    private fun findPlayerConfig(html: String): JSONObject? {
        val regex = Regex(
            """(?<!function\s)bePlayer\s*\(\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        val match = regex.find(html) ?: return null
        val hash = match.groupValues[2]
        val encrypted = match.groupValues[4]
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")

        return try {
            decryptCryptoJs(hash, encrypted)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptCryptoJs(
        password: String,
        encryptedJson: String
    ): JSONObject {
        val json = JSONObject(encryptedJson)

        val ciphertext = Base64.decode(
            json.getString("ct"),
            Base64.DEFAULT
        )

        val salt = hexToBytes(json.getString("s"))
        require(salt.size == 8)

        val digest = MessageDigest.getInstance("MD5")
        val derived = ByteArrayOutputStream()
        var previous = ByteArray(0)
        val passwordBytes = password.toByteArray(Charsets.UTF_8)

        while (derived.size() < 48) {
            digest.reset()
            digest.update(previous)
            digest.update(passwordBytes)
            digest.update(salt)
            previous = digest.digest()
            derived.write(previous)
        }

        val bytes = derived.toByteArray()
        val key = SecretKeySpec(
            bytes.copyOfRange(0, 32),
            "AES"
        )
        val iv = IvParameterSpec(
            bytes.copyOfRange(32, 48)
        )

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)

        val plaintext = String(
            cipher.doFinal(ciphertext),
            Charsets.UTF_8
        )

        return JSONObject(plaintext)
    }

    private fun hexToBytes(text: String): ByteArray {
        require(text.length % 2 == 0)

        return ByteArray(text.length / 2) { index ->
            text.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16).toByte()
        }
    }

    private fun findPlainHlsUrl(text: String): String? {
        return Regex(
            """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).find(text)?.value
    }

    private fun findStructuredMetadata(
        document: Document
    ): JSONObject? {
        return document.select("script[type=application/ld+json]")
            .mapNotNull { element ->
                try {
                    JSONObject(element.data())
                } catch (_: Exception) {
                    null
                }
            }
            .firstOrNull { item ->
                val type = item.optString("@type")
                type == "Movie" || type == "TVSeries"
            }
    }

    private fun jsonStrings(value: Any?): List<String> {
        return when (value) {
            is JSONArray -> {
                (0 until value.length())
                    .mapNotNull { index ->
                        value.optString(index)
                            .takeIf { it.isNotBlank() }
                    }
            }

            is String -> {
                value.takeIf { it.isNotBlank() }
                    ?.let { listOf(it) }
                    ?: emptyList()
            }

            else -> emptyList()
        }
    }

    private fun firstText(
        item: JSONObject,
        vararg keys: String
    ): String? {
        for (key in keys) {
            val value = item.optString(key)
            if (value.isNotBlank() && value != "null") {
                return value
            }
        }
        return null
    }

    private fun resolve(base: String, value: String): String? {
        if (value.isBlank()) return null

        return try {
            val uri = URI(base).resolve(value)
            if (uri.scheme == "http" || uri.scheme == "https") {
                uri.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
