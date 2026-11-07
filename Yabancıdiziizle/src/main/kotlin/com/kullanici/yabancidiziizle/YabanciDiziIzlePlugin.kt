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
        val regex = Regex("""https?://[^\s"'<>\\]+(?:\.m3u8|videoPlayerCdn)[^\s"'<>\\]*""")
        return regex.findAll(clean)
            .map { it.value.trimEnd('\\', '"', '\'', ',', ';', ')') }
            .distinct()
            .toList()
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
        val cleanUrl = url.substringBefore("?").ifBlank { url }
        val reqHeaders = mapOf(
            "User-Agent" to StreamHelper.MOBILE_UA,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        val text = app.get(cleanUrl, referer = referer ?: "$mainUrl/", headers = reqHeaders).text
        val streams = StreamHelper.extractStreamUrls(text)

        for (stream in streams) {
            callback(
                newExtractorLink(name, "$name (HLS)", stream, ExtractorLinkType.M3U8) {
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
            "Accept-Language" to "tr-TR,tr;q=0.9"
        )
        val res = app.get(url, referer = referer ?: "$mainUrl/", headers = reqHeaders)
        val text = res.text

        // 1) Doğrudan veya Unpacked JS içindeki .m3u8 / .mp4 linkleri
        val directStreams = StreamHelper.extractStreamUrls(text)
        for (stream in directStreams) {
            callback(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to StreamHelper.MOBILE_UA,
                        "Referer" to url
                    )
                }
            )
        }

        // 2) FirePlayer / AJAX POST yapısındaysa (/player/index.php?data=...&do=getVideo)
        val fireMatch = Regex("""FirePlayer\s*\(\s*["']([a-zA-Z0-9]+)["']""").find(text)
        if (fireMatch != null && directStreams.isEmpty()) {
            val hash = fireMatch.groupValues[1]
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
                data = mapOf("hash" to hash, "r" to (referer ?: ""))
            ).text

            val cleanPost = StreamHelper.unescapeHtmlAndJson(postText)
            val videoSrc = Regex(""""videoSource"\s*:\s*"([^"]+)"""").find(cleanPost)?.groupValues?.getOrNull(1)
                ?: Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(cleanPost)?.groupValues?.getOrNull(1)

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
                         
