package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URL
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

    /**
     * Canlı sitede doğrulananlar: korku, dram, populer-filmler,
     * en-cok-izlenen-filmler, yerli-film-izle.
     * Diğerleri aynı adlandırma düzenini izliyor ama ayrıca
     * doğrulanamadı; bir kategori boş dönerse yalnızca o satır boş kalır.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/filmizle/yerli-film-izle" to "Yerli Filmler",
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
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler"
    )

    // Yalnızca tek parçalı detay adreslerini kabul eder:
    // /film/slug/ veya /dizi/slug/ (sezon/bölüm adresleri hariç).
    private val detailRegex =
        Regex("""^https?://[^/]+/(?:film|dizi)/[^/?#]+/?$""")

    private val yearRegex =
        Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")

    private val scoreRegex =
        Regex("""(?<![\d.,])\d{1,2}[.,]\d(?!\d)""")

    /**
     * Sayfalama yol tabanlıdır (doğrulandı):
     * /filmizle/korku-filmleri-izle  ->  /filmizle/korku-filmleri-izle/2
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/$page"
        }

        val document = app.get(pageUrl, referer = "$mainUrl/").document
        val results = parseCards(document)

        val nextPath = "/${page + 1}"
        val hasNextLink = document.select("a[href]").any { link ->
            link.attr("href").trimEnd('/').endsWith(nextPath) ||
                link.text().trim().equals("İleri", ignoreCase = true)
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && hasNextLink
        )
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val movieCards = document.select(
            "article.movie_box a.image, article.movie_box > a[href]"
        )

        val seriesCards = document.select("main a[href*='/dizi/']")

        var cards: List<Element> = movieCards + seriesCards

        // Bilinen kart yapısı bulunamazsa tüm detay bağlantılarını dene.
        if (cards.none { detailRegex.matches(it.attr("abs:href")) }) {
            cards = document.select("a[href*='/film/'], a[href*='/dizi/']")
        }

        return cards
            .mapNotNull { card -> card.toSearchItem() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val rawHref = attr("abs:href").ifBlank { attr("href") }
        val href = resolve(mainUrl, rawHref) ?: return null
        if (!detailRegex.matches(href)) return null

        // Bağlantı kartın tamamını sarıyorsa kendisi, yalnızca görsel
        // içeriyorsa çevreleyen article/üst öğe kart sayılır.
        val card = if (text().length > 12) {
            this
        } else {
            parents().firstOrNull { it.tagName() == "article" }
                ?: parent()
                ?: this
        }

        val title = cleanTitle(attr("title"))
            .takeIf { it.isNotEmpty() }
            ?: card.selectFirst("h2, h3, .title, strong")
                ?.text()
                ?.let { cleanTitle(it) }
                ?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img[alt]")
                ?.attr("alt")
                ?.let { cleanTitle(it) }
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val image = card.selectFirst("img") ?: selectFirst("img")
        val posterRaw = image?.let { img ->
            listOf("data-src", "data-lazy-src", "data-original", "src")
                .map { key -> img.attr(key) }
                .firstOrNull { value ->
                    value.isNotBlank() && !value.startsWith("data:")
                }
        }
        val poster = posterRaw?.let { resolve(href, it) }

        val cardText = card.text()

        val year = card.selectFirst(".year")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: yearRegex.findAll(cardText).lastOrNull()
                ?.value
                ?.toIntOrNull()

        val scoreSource = card.selectFirst(".imdb")?.text() ?: cardText
        val scoreText = scoreRegex.find(scoreSource)
            ?.value
            ?.replace(',', '.')
            ?.takeIf { (it.toDoubleOrNull() ?: -1.0) in 0.0..10.0 }

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
     * Arama adresi /arama/oneri sayfada görüldü, ancak q parametresi ve
     * yanıt biçimi (HTML veya JSON) doğrulanamadı. Bu yüzden birkaç
     * adayı sırayla dener ve ilk sonuç veren yanıtı kullanır.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoints = listOf(
            "$mainUrl/arama/oneri?q=$encoded",
            "$mainUrl/arama?q=$encoded"
        )

        for (endpoint in endpoints) {
            val body = try {
                app.get(
                    endpoint,
                    referer = "$mainUrl/",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }

            val results = parseSearchJson(body).ifEmpty {
                parseCards(Jsoup.parse(body, mainUrl))
            }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    private fun parseSearchJson(body: String): List<SearchResponse> {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return emptyList()
        }

        val root: Any? = try {
            JSONTokener(trimmed).nextValue()
        } catch (e: Exception) {
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
                "title", "name", "baslik", "ad", "label"
            )?.let { cleanTitle(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val rawUrl = firstText(
                item,
                "url", "link", "href", "permalink"
            ) ?: return@mapNotNull null

            val url = resolve(mainUrl, rawUrl)
                ?: return@mapNotNull null

            if (!detailRegex.matches(url)) return@mapNotNull null

            val poster = firstText(
                item,
                "poster", "image", "img", "cover", "resim"
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
        val document = app.get(url, referer = "$mainUrl/").document
        val metadata = findStructuredMetadata(document)

        val title = (
            metadata.str("name")
                ?: document.selectFirst("main h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
            )
            ?.let { cleanTitle(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = jsonImage(metadata?.opt("image"))
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val description = metadata.str("description")
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val year = listOf("dateCreated", "datePublished", "copyrightYear")
            .firstNotNullOfOrNull { key ->
                metadata.str(key)?.take(4)?.toIntOrNull()
            }
            ?: Regex("""\((\d{4})\)""")
                .find(
                    document.selectFirst("meta[property=og:title]")
                        ?.attr("content")
                        ?: document.title()
                )
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            ?: document.selectFirst("a[href*='/yil/']")
                ?.text()
                ?.trim()
                ?.take(4)
                ?.toIntOrNull()

        val rating = metadata
            ?.optJSONObject("aggregateRating")
            .str("ratingValue")

        val genres = jsonStrings(metadata?.opt("genre"))
        val actors = jsonActors(metadata?.opt("actor"))

        if (url.contains("/dizi/")) {
            val episodes = document.select(
                "a[href*='/sezon-'][href*='/bolum-']"
            ).mapNotNull { element ->
                val episodeUrl = resolve(
                    url,
                    element.attr("abs:href").ifBlank {
                        element.attr("href")
                    }
                ) ?: return@mapNotNull null

                val match = Regex(
                    """/sezon-(\d+)/bolum-(\d+)"""
                ).find(episodeUrl)
                    ?: return@mapNotNull null

                val seasonNumber =
                    match.groupValues[1].toIntOrNull()

                val episodeNumber =
                    match.groupValues[2].toIntOrNull()

                val episodeName = element.attr("title")
                    .takeIf { it.isNotBlank() }
                    ?: element.text().trim().takeIf { it.isNotEmpty() }

                newEpisode(episodeUrl) {
                    this.name = episodeName
                    this.season = seasonNumber
                    this.episode = episodeNumber
                }
            }.distinctBy { it.data }
                .sortedWith(
                    compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 })
                )

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
     * En iyi çaba (oynatıcı mekanizması canlı siteden doğrulanamadı):
     * 1. Sayfanın kendisinde bePlayer(...) veya açık .m3u8 varsa kullan.
     * 2. Sayfadaki iframe'leri CloudStream extractor'larına ilet.
     * 3. Her iframe'in HTML'inde bePlayer(...) / .m3u8 ara.
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
            val page = app.get(data, referer = "$mainUrl/")
            val document = page.document

            if (emitStream(page.text, data, callback)) {
                foundLink = true
            }

            var frames = document.select(
                "#f_player iframe, #fm_cerceve iframe"
            )
            if (frames.isEmpty()) {
                frames = document.select("iframe")
            }

            val frameUrls = frames.mapNotNull { frame ->
                resolve(
                    data,
                    frame.attr("src").ifBlank { frame.attr("data-src") }
                )
            }.distinct()

            for (iframeUrl in frameUrls) {
                try {
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                        countingCallback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Diğer yöntemleri dene.
                }

                val iframeHtml = try {
                    app.get(iframeUrl, referer = data).text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }

                if (emitStream(iframeHtml, iframeUrl, callback)) {
                    foundLink = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return foundLink
        }

        return foundLink
    }

    private suspend fun emitStream(
        html: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val config = findPlayerConfig(html)

        val raw = config?.let { findConfigStream(it) }
            ?: findPlainHlsUrl(html)

        val stream = raw?.let { resolve(base, it) } ?: return false

        val linkType = if (stream.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = stream,
                type = linkType
            ) {
                referer = base
            }
        )

        return true
    }

    private fun findConfigStream(config: JSONObject): String? {
        return firstText(
            config,
            "video_location", "file", "source", "src"
        )
    }

    /**
     * bePlayer("hash", "{\"ct\":...}") çağrısını bulur. Dize değişmezleri
     * kaçış karakterlerini (\" \' \\) doğru işler; böylece çift tırnaklı
     * JS dizesinin içindeki JSON tırnakları ayrıştırmayı bozmaz.
     * Argümanlar değişken ise (değişmez değilse) bulunamaz.
     */
    private fun findPlayerConfig(html: String): JSONObject? {
        val regex = Regex(
            """(?<!function\s)bePlayer\s*\(\s*(['"])((?:\\.|(?!\1)[^\\])*)\1\s*,\s*(['"])((?:\\.|(?!\3)[^\\])*)\3""",
            RegexOption.DOT_MATCHES_ALL
        )

        val match = regex.find(html) ?: return null
        val hash = unescapeJs(match.groupValues[2])
        val encrypted = unescapeJs(match.groupValues[4])

        return try {
            decryptCryptoJs(hash, encrypted)
        } catch (e: Exception) {
            null
        }
    }

    private fun unescapeJs(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            val c = text[i]

            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }

            when (val next = text[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    val code = if (i + 6 <= text.length) {
                        text.substring(i + 2, i + 6).toIntOrNull(16)
                    } else {
                        null
                    }

                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(next)
                        i += 2
                    }
                }
                else -> { out.append(next); i += 2 }
            }
        }

        return out.toString()
    }

    /**
     * CryptoJS parola modu: anahtar ve IV, MD5 tabanlı EVP_BytesToKey ile
     * parola + tuzdan türetilir (JSON'daki "iv" alanı kullanılmaz).
     * Çözülen metin çift kodlanmış JSON olabilir; ikisi de desteklenir.
     */
    private fun decryptCryptoJs(
        password: String,
        encryptedJson: String
    ): JSONObject {
        val json = JSONObject(encryptedJson)

        val 
