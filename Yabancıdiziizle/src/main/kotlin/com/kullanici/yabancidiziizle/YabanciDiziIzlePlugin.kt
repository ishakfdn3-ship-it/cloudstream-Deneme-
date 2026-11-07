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
        Regex("""https?:\\?/\?/[^"'\s\\]+\.m3u8[^"'\s\\]*""").findAll(text)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&") }
            .distinct()
            .forEach { stream ->
                callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
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
        val text = app.get(url, referer = referer ?: "$mainUrl/").text
        val combined = "$text\n${getAndUnpack(text)}"
        Regex("""https?:\\?/\?/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""").findAll(combined)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&") }
            .distinct()
            .forEach { stream ->
                val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(name, name, stream, type) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
    }
}
