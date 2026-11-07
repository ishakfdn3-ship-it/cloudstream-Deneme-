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

// Doğrulandı: 6_Embed_JWPlayer_Sayfasi.html ve 7_Video_Kaynak_Bilgisi.json
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
        val text = app.get(url, referer = referer ?: "$mainUrl/").text
        val m3u8Regex = Regex("""https?:\\?/\?/[a-zA-Z0-9.\-]*okcdn\.ru\\?/video\.m3u8[^"'\s\\]*|https?:\\?/\?/[^"'\s\\]+\.m3u8[^"'\s\\]*""")
        val links = m3u8Regex.findAll(text)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&") }
            .distinct()
            .toList()

        for (streamUrl in links) {
            callback(
                newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// Doğrulandı: 5_Bolum_Sayfasi.html (iframe src="https://luffytra2.online/embed/?...")
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
        val responseText = app.get(url, referer = referer ?: "$mainUrl/").text
        val unpacked = getAndUnpack(responseText)
        val combined = "$responseText\n$unpacked"

        val streamRegex = Regex("""https?:\\?/\?/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
        val links = streamRegex.findAll(combined)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&") }
            .distinct()
            .toList()

        for (streamUrl in links) {
            val linkType = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, name, streamUrl, linkType) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
