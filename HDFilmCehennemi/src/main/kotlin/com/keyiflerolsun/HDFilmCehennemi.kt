package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private val embedBase = "https://hdfilmcehennemi.mobi"

    override val mainPage = mainPageOf(
        "${mainUrl}/category/film-izle-2/"            to "Yeni Filmler",
        "${mainUrl}/category/tavsiye-filmler-izle3/"  to "Tavsiye Filmler",
        "${mainUrl}/category/1080p-hd-film-izle-2/"   to "1080p Filmler",
        "${mainUrl}/yabancidiziizle-6/"               to "Yabancı Diziler",
        "${mainUrl}/yil/2025-filmleri-izle-3/"        to "2025 Filmleri",
        "${mainUrl}/yil/2024-2/"                      to "2024 Filmleri"
    )

    // ---------- Güvenli HTML/JSoup yardımcıları ----------

    private fun safeText(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.text() } catch (e: Throwable) { null }
    }

    private fun safeAttr(el: Element?, key: String): String? {
        if (el == null) return null
        return try { "" + el.attr(key) } catch (e: Throwable) { null }
    }

    private fun safeData(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.data() } catch (e: Throwable) { null }
    }

    private fun safeOwnText(el: Element?): String? {
        if (el == null) return null
        return try { "" + el.ownText() } catch (e: Throwable) { null }
    }

    private fun unpackScript(packed: String): String {
        return try {
            val clazz = Class.forName("com.lagradost.common.JsUnpacker")
            val ctor = clazz.getConstructor(String::class.java)
            val instance = ctor.newInstance(packed)
            val method = clazz.getMethod("unpack")
            (method.invoke(instance) as? String) ?: packed
        } catch (e: Throwable) {
            packed
        }
    }

    private fun findGroupInt(input: String, pattern: String, group: Int = 1): Int? {
        return try {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(input) ?: return null
            if (match.groupValues.size <= group) return null
            match.groupValues[group].toIntOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- Ana sayfa ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl  = request.data.trimEnd('/')
        val url      = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val document = app.get(url).document

        val home = document.select("a.poster, div.poster-card, article.poster").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val hrefRaw: String? = safeAttr(anchor, "href")
        val href: String = fixUrlNull(hrefRaw) ?: return null

        val titleA: String? = safeText(this.selectFirst("h2, h3, .title, .poster-title, strong"))
        val titleB: String? = safeAttr(anchor, "title")?.ifEmpty { null }
        val titleC: String? = safeAttr(this.selectFirst("img"), "alt")?.ifEmpty { null }
        val title: String = titleA ?: titleB ?: titleC ?: return null

        val cleanTitle = title.replace("izle", "", ignoreCase = true).trim()

        val img = this.selectFirst("img")
        val posterSrc: String? = safeAttr(img, "data-src")?.ifEmpty { safeAttr(img, "src") }
        val posterUrl: String? = fixUrlNull(posterSrc)

        val selfText: String? = safeText(this)
        val isSeries: Boolean = href.contains("dizi", ignoreCase = true) ||
                                selfText?.contains("Sezon", ignoreCase = true) == true

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response: String = app.get(
            "${mainUrl}/search/?q=${query}",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "Referer"          to "$mainUrl/"
            )
        ).text

        val results = mutableListOf<SearchResponse>()

        val jsonSearch: SearchJson? = tryParseJson(response)
        val jsonResults = jsonSearch?.results
        if (jsonResults != null && jsonResults.isNotEmpty()) {
            for (htmlItem in jsonResults) {
                val doc = Jsoup.parse(htmlItem)
                val first = doc.selectFirst("a, div.search-item")
                val result = first?.toSearchResult()
                if (result != null) results.add(result)
            }
            if (results.isNotEmpty()) return results
        }

        val doc = Jsoup.parse(response)
        return doc.select("a.poster, a.search-item, div.result-group a").mapNotNull { it.toSearchResult() }
    }

    // ---------- Detay ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val ldScriptEl: Element? = document.selectFirst("script[type=application/ld+json]")
        val ldJsonScript: String? = safeData(ldScriptEl)
        val ldData: LdJson? = tryParseJson(ldJsonScript)

        val rawTitleA: String? = ldData?.name
        val rawTitleB: String? = safeOwnText(document.selectFirst("h1.section-title"))
            ?.replace("izle", "", ignoreCase = true)?.trim()
        val rawTitle: String = rawTitleA ?: rawTitleB ?: return null

        val subTitle: String = safeText(document.selectFirst("h1.section-title small")) ?: ""
        val yearFromSub: Int? = findGroupInt(subTitle, """\((\d{4})\)""")
        val yearFromLd: Int? = ldData?.datePublished?.let {
            if (it.length >= 4) it.substring(0, 4).toIntOrNull() else null
        }
        val year: Int? = yearFromSub ?: yearFromLd

        val posterEl = document.selectFirst("div.post-info-poster img, img.poster")
        val posterSrc: String? = ldData?.image
            ?: safeAttr(posterEl, "data-src")?.ifEmpty { safeAttr(posterEl, "src") }
        val poster: String? = fixUrlNull(posterSrc)

        val description: String? = ldData?.description
            ?: safeText(document.selectFirst("article.post-info-content p, div.post-info-content"))?.trim()

        val tags: List<String> = ldData?.genre
            ?: document.select("div.post-info-genres a").map { safeText(it) ?: "" }.filter { it.isNotEmpty() }
        val actors: List<String> = ldData?.actor?.mapNotNull { it.name } ?: emptyList()
        val imdbId: String? = ldData?.sameAs?.firstOrNull { it.contains("imdb.com") }?.substringAfterLast("/")

        val episodeElements = document.select("div.seasons-tab-content a, div.episodes-list a, a.episode-item")
        val isSeries: Boolean = episodeElements.isNotEmpty() || ldData?.type.equals("TVSeries", ignoreCase = true)

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHrefRaw: String? = safeAttr(ep, "href")
                val epHref: String = fixUrlNull(epHrefRaw) ?: return@mapNotNull null
                val epName: String = safeText(ep) ?: ""

                val seasonParent = ep.parents().firstOrNull { it.hasAttr("data-season") }
                val seasonFromName: Int? = findGroupInt(epName, """(\d+)\.\s*Sezon""")
                val seasonFromAttr: Int? = safeAttr(seasonParent, "data-season")?.toIntOrNull()
                val seasonNum: Int = seasonFromName ?: seasonFromAttr ?: 1

                val episodeNum: Int? = findGroupInt(epName, """(\d+)\.\s*Bölüm""")

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = seasonNum
                    this.episode = episodeNum
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot      = description
                    this.year      = year
                    this.tags      = tags
                    addActors(actors)
                    addImdbId(imdbId)
                }
            }
        }

        return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addImdbId(imdbId)
        }
    }

    // ---------- Video Linkleri ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val processedUrls = mutableSetOf<String>()

        // 1) Ana oynatıcı iframe'i (.mobi dahil)
        val mainIframe = document.selectFirst(
            "div.video-container iframe, iframe[src*='hdfilmcehennemi.mobi'], iframe[data-src*='hdfilmcehennemi.mobi']"
        )
        if (mainIframe != null) {
            val iframeSrc = fixUrlNull(
                safeAttr(mainIframe, "data-src")?.ifEmpty { safeAttr(mainIframe, "src") }
            )
            if (iframeSrc != null && processedUrls.add(iframeSrc)) {
                extractVideo(iframeSrc, data, "Varsayılan", subtitleCallback, callback)
            }
        }

        // 2) Alternatif kaynak butonları
        for (button in document.select("button.alternative-link, [data-video]")) {
            val videoId = safeAttr(button, "data-video")
            if (videoId.isNullOrBlank()) continue

            val langParent = button.parents().firstOrNull { it.hasClass("alternative-links") }
            val label = "${safeAttr(langParent, "data-lang")?.uppercase() ?: "TR"} - ${safeText(button)?.trim() ?: "Kaynak"}"

            val responseText = try {
                app.get(
                    "${mainUrl}/video/$videoId/",
                    headers = mapOf("X-Requested-With" to "fetch", "Referer" to data)
                ).text
            } catch (e: Throwable) {
                null
            }
            if (responseText == null) continue

            val cleanHtml = responseText.replace("\\\"", "\"").replace("\\/", "/")
            val iframeDoc = Jsoup.parse(cleanHtml)
            val iframeEl = iframeDoc.selectFirst("iframe")

            val iframeFromTag = safeAttr(iframeEl, "data-src")?.ifEmpty { safeAttr(iframeEl, "src") }
            val iframeFromRegex = Regex("""src=["'](https?://[^"']+)["']""").find(cleanHtml)?.groupValues?.get(1)
            val iframeSrc = fixUrlNull(iframeFromTag ?: iframeFromRegex)

            if (iframeSrc != null && processedUrls.add(iframeSrc)) {
                extractVideo(iframeSrc, data, label, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun extractVideo(
        iframeUrl: String,
        referer: String,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDFC", "[$sourceLabel] extractVideo → $iframeUrl")
        try {
            if (loadExtractor(iframeUrl, referer, subtitleCallback, callback)) {
                Log.d("HDFC", "[$sourceLabel] loadExtractor ✅")
                return
            }
        } catch (e: Throwable) {
            Log.d("HDFC", "loadExtractor ❌ ${e.message}")
        }

        try {
            val effectiveReferer = if (iframeUrl.contains("hdfilmcehennemi.mobi")) "$mainUrl/" else referer
            val response = app.get(
                iframeUrl,
                referer = effectiveReferer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )
            val html = response.text
            val doc = response.document

            // A) Altyazılar (video track etiketlerinden)
            doc.select("video track").forEach { track ->
                val rawSrc = safeAttr(track, "src")
                val subUrl = if (rawSrc == null) null
                             else if (rawSrc.startsWith("http")) rawSrc
                             else "$embedBase$rawSrc"
                val lang = safeAttr(track, "srclang") ?: "unknown"
                val label = safeAttr(track, "label") ?: ""
                if (subUrl != null) {
                    subtitleCallback.invoke(SubtitleFile("$label ($lang)".trim(), subUrl))
                }
            }

            // B) Packed script arama
            var packedScript: String? = null
            for (script in doc.select("script")) {
                val data = safeData(script) ?: continue
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    packedScript = data
                    break
                }
            }

            val packed = packedScript
            if (packed != null) {
                val unpacked = unpackScript(packed)
                val partsMatch = Regex("""dc_\w+\(\s*\[([^\]]+)\]\s*\)""").find(unpacked)
                if (partsMatch != null) {
                    val parts = Regex("""["']([^"']+)["']""")
                        .findAll(partsMatch.groupValues[1])
                        .map { it.groupValues[1] }
                        .toList()
                    if (parts.isNotEmpty()) {
                        val videoUrl = decodeVideoUrl(parts)
                        if (videoUrl != null) {
                            Log.d("HDFC", "[$sourceLabel] ✅ video URL: $videoUrl")
                            M3u8Helper.generateM3u8(
                                source = "${this.name} ($sourceLabel)",
                                streamUrl = videoUrl,
                                referer = iframeUrl
                            ).forEach(callback)
                            return
                        }
                    }
                }
            }

            // C) Yedek: doğrudan m3u8 / mp4
            val directUrl = Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""").find(html)?.value
            if (directUrl != null) {
                Log.d("HDFC", "[$sourceLabel] fallback direct → $directUrl")
                M3u8Helper.generateM3u8(
                    source = "${this.name} ($sourceLabel)",
                    streamUrl = directUrl,
                    referer = iframeUrl
                ).forEach(callback)
            }
        } catch (e: Throwable) {
            Log.e("HDFC", "[$sourceLabel] hata: ${e.message}")
        }
    }

    // ---------- Şifreleme Çözücü ----------

    private fun decodeVideoUrl(parts: List<String>): String? {
        val raw = parts.joinToString("")
        val reversed = raw.reversed()

        // Variant 3 (en güncel): Base64 → Reverse → ROT13 → Unmix
        decodeVariant3(raw)?.let { return it }
        // Variant 1: ROT13 → Base64 → Unmix
        decodeVariant1(reversed)?.let { return it }
        // Variant 2: Base64 → ROT13 → Unmix
        decodeVariant2(reversed)?.let { return it }

        return null
    }

    private fun decodeVariant1(reversed: String): String? {
        return try {
            val rot13 = rot13(reversed)
            val b64 = String(Base64.decode(rot13, Base64.DEFAULT), Charsets.ISO_8859_1)
            characterUnmix(b64).takeIf { isValidVideoUrl(it) }
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeVariant2(reversed: String): String? {
        return try {
            val b64 = String(Base64.decode(reversed, Base64.DEFAULT), Charsets.ISO_8859_1)
            val rot13 = rot13(b64)
            characterUnmix(rot13).takeIf { isValidVideoUrl(it) }
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeVariant3(value: String): String? {
        return try {
            val b64 = String(Base64.decode(value, Base64.DEFAULT), Charsets.ISO_8859_1)
            val reversed = b64.reversed()
            val rot13 = rot13(reversed)
            characterUnmix(rot13).takeIf { isValidVideoUrl(it) }
        } catch (e: Throwable) {
            null
        }
    }

    private fun rot13(str: String): String {
        return str.map { c ->
            when {
                c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        }.joinToString("")
    }

    private fun characterUnmix(value: String): String {
        val sb = StringBuilder()
        for (i in value.indices) {
            var charCode = value[i].code
            charCode = (charCode - (399756995 % (i + 5)) + 256) % 256
            sb.append(charCode.toChar())
        }
        return sb.toString()
    }

    private fun isValidVideoUrl(url: String?): Boolean {
        return url != null && url.startsWith("https://") &&
                (url.contains(".m3u8") || url.contains("/hls/") || url.contains(".mp4"))
    }

    // ---------- Data class'lar ----------

    data class SearchJson(
        @JsonProperty("results") val results: List<String>? = null
    )

    data class LdJson(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("datePublished") val datePublished: String? = null,
        @JsonProperty("genre") val genre: List<String>? = null,
        @JsonProperty("sameAs") val sameAs: List<String>? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("actor") val actor: List<LdActor>? = null
    )

    data class LdActor(
        @JsonProperty("name") val name: String? = null
    )
}
