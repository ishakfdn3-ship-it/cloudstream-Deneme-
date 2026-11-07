herepackage com.kullanici.yabancidiziizle

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class YabanciDiziIzlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YabanciDiziIzle())
        registerExtractorAPI(DzenRuExtractor())
        registerExtractorAPI(LuffytraExtractor())
    }
}

internal object StreamHelper {
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun unescapeHtmlAndJson(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("&amp;", "&")
    }

    data class Stream(val url: String, val type: ExtractorLinkType, val quality: Int)

    private fun qualityOf(label: String): Int = when (label.lowercase()) {
        "tiny" -> 144
        "lowest" -> 240
        "low" -> 360
        "medium" -> 480
        "high" -> 720
        "fullhd" -> 1080
        else -> Qualities.Unknown.value
    }

    private fun typeOfUrl(u: String): ExtractorLinkType = when {
        u.contains(".mpd") || u.contains("dzen_dash=dash") -> ExtractorLinkType.DASH
        u.contains(".m3u8") || u.contains("video.m3u8") || u.contains("dzen_dash=hls") || u.contains("/hls/") -> ExtractorLinkType.M3U8
        else -> ExtractorLinkType.VIDEO
    }

    // Dzen embed sayfasındaki "streams":[{"url":"...","type":"hls"|"dash"|"tiny"|...}] yapısını okur.
    fun extractStreams(rawText: String): List<Stream> {
        val unpacked = try { getAndUnpack(rawText) } catch (_: Throwable) { "" }
        val clean = unescapeHtmlAndJson("$rawText\n$unpacked")
        val out = LinkedHashMap<String, Stream>()

        val pair = Regex(""""url"\s*:\s*"(https?://[^"]+)"\s*,\s*"type"\s*:\s*"([a-zA-Z0-9_]+)"""")
        for (m in pair.findAll(clean)) {
            val u = m.groupValues[1]
            val t = m.groupValues[2].lowercase()
            if (u.contains("avatars.") || u.endsWith(".jpg") || u.endsWith(".png")) continue
            val s = when (t) {
                "hls" -> Stream(u, ExtractorLinkType.M3U8, Qualities.Unknown.value)
                "dash" -> Stream(u, ExtractorLinkType.DASH, Qualities.Unknown.value)
                else -> Stream(u, ExtractorLinkType.VIDEO, qualityOf(t))
            }
            out[u] = s
        }

        // Yedek: genel m3u8 / mp4 kalıpları
        val urlRegex = Regex("""https?://[^\s"'<>\\]+?(?:\.m3u8|video\.m3u8|videoPlayerCdn|/hls/|\.mp4)[^\s"'<>\\]*""")
        for (m in urlRegex.findAll(clean)) {
            val u = m.value.trimEnd('\\', '"', '\'', ',', ';', ')', ']')
            if (u.contains("avatars.") || u.endsWith(".jpg") || u.endsWith(".png")) continue
            if (!out.containsKey(u)) out[u] = Stream(u, typeOfUrl(u), Qualities.Unknown.value)
        }
        return out.values.toList()
    }

    fun extractStreamUrls(rawText: String): List<String> = extractStreams(rawText).map { it.url }
}

class DzenRuExtractor : ExtractorApi() {
    override val name = "DzenRu"
    override val mainUrl = "https://dzen.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val foundStreams = LinkedHashMap<String, StreamHelper.Stream>()
        val embedId = url.substringAfter("/embed/").substringBefore("?").substringBefore("/").trim()

        val commonHeaders = mapOf(
            "User-Agent" to StreamHelper.MOBILE_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )

        // ADIM 1: Yandex VH Player JSON API (og:vh-uuid üzerinden doğrudan JSON çekimi)
        if (embedId.isNotBlank()) {
            val apiUrls = listOf(
                "https://frontend.vh.yandex.ru/player/$embedId.json?from=dzen&service=ya-video",
                "https://frontend.vh.yandex.ru/v23/player/$embedId.json?from=dzen&service=ya-video"
            )
            for (apiUrl in apiUrls) {
                try {
                    val apiText = app.get(
                        apiUrl,
                        referer = "https://dzen.ru/",
                        headers = mapOf(
                            "User-Agent" to StreamHelper.MOBILE_UA,
                            "Accept" to "application/json",
                            "Origin" to "https://dzen.ru"
                        )
                    ).text
                    StreamHelper.extractStreams(apiText).forEach { foundStreams[it.url] = it }
                } catch (_: Exception) {
                }
            }
        }

        // ADIM 2: Dzen Embed HTML sayfası (Çerez ve yönlendirme takibi ile)
        try {
            val cleanEmbedUrl = if (embedId.isNotBlank()) "https://dzen.ru/embed/$embedId" else url
            val firstRes = app.get(cleanEmbedUrl, referer = referer ?: "https://yabancidiziizle.in/", headers = commonHeaders)
            StreamHelper.extractStreams(firstRes.text).forEach { foundStreams[it.url] = it }

            // Eğer ilk istekte çerez atandıysa veya _csrf yönlendirmesi varsa çerezlerle tekrar çek
            if (foundStreams.isEmpty() && firstRes.cookies.isNotEmpty()) {
                val secondRes = app.get(
                    cleanEmbedUrl,
                    referer = "https://dzen.ru/",
                    headers = commonHeaders,
                    cookies = firstRes.cookies
                )
                StreamHelper.extractStreams(secondRes.text).forEach { foundStreams[it.url] = it }
            }
        } catch (_: Exception) {
        }

        for (s in foundStreams.values) {
            callback(
                newExtractorLink(name, "$name ${if (s.quality > 0) s.quality.toString() + "p" else s.type.name}", s.url, s.type) {
                    this.referer = "https://dzen.ru/"
                    this.quality = s.quality
                    this.headers = mapOf(
                        "User-Agent" to StreamHelper.MOBILE_UA,
                        "Origin" to "https://dzen.ru",
                        "Referer" to "https://dzen.ru/"
                    )
                }
            )
        }
    }
}

class LuffytraExtractor : ExtractorApi() {
    override val name = "Luffytra"
    override val mainUrl = "https://luffytra2.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val reqHeaders = mapOf(
            "User-Agent" to StreamHelper.MOBILE_UA,
            "Accept-Language" to "tr-TR,tr;q=0.9",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val res = app.get(url, referer = referer ?: "https://yabancidiziizle.in/", headers = reqHeaders)
        val text = res.text

        // 1) Doğrudan veya Unpacked JS içindeki stream linkleri
        val directStreams = StreamHelper.extractStreamUrls(text)
        for (stream in directStreams) {
            callback(
                newExtractorLink(name, name, stream, if (stream.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to StreamHelper.MOBILE_UA, "Referer" to url)
                }
            )
        }

        // 2) FirePlayer / Pichive / GDrivePlayer tarzı AJAX POST kontrolü
        val hashMatch = Regex("""(?:FirePlayer|Player)\s*\(\s*["']([a-zA-Z0-9_\-=]+)["']""").find(text)
            ?: Regex("""data\s*[:=]\s*["']([a-zA-Z0-9_\-=]{16,})["']""").find(text)

        if (hashMatch != null && directStreams.isEmpty()) {
            val hash = hashMatch.groupValues[1]
            val host = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
            val ajaxUrl = "$host/player/index.php?data=$hash&do=getVideo"
            val postText = app.post(
                ajaxUrl,
                headers = mapOf(
                    "User-Agent" to StreamHelper.MOBILE_UA,
                    "Referer" to url,
                    "Origin" to host,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("hash" to hash, "r" to (referer ?: "https://yabancidiziizle.in/"))
            ).text

            val cleanPost = StreamHelper.unescapeHtmlAndJson(postText)
            val videoSrc = Regex(""""(?:videoSource|securedLink|file)"\s*:\s*"([^"]+)"""")
                .find(cleanPost)?.groupValues?.getOrNull(1)

            if (!videoSrc.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, name, videoSrc, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to StreamHelper.MOBILE_UA, "Referer" to url)
                    }
                )
            }
        }
    }
}
