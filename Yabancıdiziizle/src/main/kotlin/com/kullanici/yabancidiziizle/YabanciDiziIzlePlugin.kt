package com.kullanici.yabancidiziizle

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

    fun extractStreamUrls(rawText: String): List<String> {
        val unpacked = getAndUnpack(rawText)
        val clean = unescapeHtmlAndJson("$rawText\n$unpacked")

        val results = mutableSetOf<String>()

        // 1) okcdn.ru / video.m3u8 / strm.yandex.net / genel .m3u8 kalıpları
        val urlRegex = Regex("""https?://[^\s"'<>\\]+(?:\.m3u8|video\.m3u8|videoPlayerCdn|/hls/)[^\s"'<>\\]*""")
        urlRegex.findAll(clean).forEach { m ->
            results.add(m.value.trimEnd('\\', '"', '\'', ',', ';', ')', ']'))
        }

        // 2) Dzen JSON içindeki "url":"https://..." (okcdn.ru veya yandex streamleri)
        val jsonUrlRegex = Regex(""""url"\s*:\s*"(https?://[^"]+(?:okcdn\.ru|yandex\.net|dzeninfra\.ru|\.m3u8|\.mp4)[^"]*)"""")
        jsonUrlRegex.findAll(clean).forEach { m ->
            val u = m.groupValues[1]
            if (!u.contains("avatars.") && !u.endsWith(".jpg") && !u.endsWith(".png")) {
                results.add(u)
            }
        }

        return results.toList()
    }
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
        val foundStreams = mutableSetOf<String>()
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
                    foundStreams.addAll(StreamHelper.extractStreamUrls(apiText))
                } catch (_: Exception) {
                }
            }
        }

        // ADIM 2: Dzen Embed HTML sayfası (Çerez ve yönlendirme takibi ile)
        try {
            val cleanEmbedUrl = if (embedId.isNotBlank()) "https://dzen.ru/embed/$embedId" else url
            val firstRes = app.get(cleanEmbedUrl, referer = referer ?: "https://yabancidiziizle.in/", headers = commonHeaders)
            foundStreams.addAll(StreamHelper.extractStreamUrls(firstRes.text))

            // Eğer ilk istekte çerez atandıysa veya _csrf yönlendirmesi varsa çerezlerle tekrar çek
            if (foundStreams.isEmpty() && firstRes.cookies.isNotEmpty()) {
                val secondRes = app.get(
                    cleanEmbedUrl,
                    referer = "https://dzen.ru/",
                    headers = commonHeaders,
                    cookies = firstRes.cookies
                )
                foundStreams.addAll(StreamHelper.extractStreamUrls(secondRes.text))
            }
        } catch (_: Exception) {
        }

        for (stream in foundStreams) {
            val linkType = when {
                stream.contains(".mpd") -> ExtractorLinkType.DASH
                stream.contains(".m3u8") || stream.contains("video.m3u8") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, "$name (HLS)", stream, linkType) {
                    this.referer = "https://dzen.ru/"
                    this.quality = Qualities.Unknown.value
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
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
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
                                 
