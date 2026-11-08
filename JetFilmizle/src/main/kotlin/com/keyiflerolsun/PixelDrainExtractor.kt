package com.kullanici.jetfilmizle

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

open class VideoParkExtractor : ExtractorApi() {
    override val name = "VideoPark"
    override val mainUrl = "https://videopark.top"
    override val requiresReferer = true

    data class VPTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    data class VPSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    data class VPInitialData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("sources") val sources: List<VPSource>? = null,
        @JsonProperty("tracks") val tracks: List<VPTrack>? = null,
        @JsonProperty("has_dub") val hasDub: Boolean? = null,
        @JsonProperty("code") val code: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "https://jetfilmizle.app/"
        val html = app.get(
            url,
            referer = ref,
            headers = mapOf("Accept-Language" to "tr-TR,tr;q=0.9")
        ).text

        val initialJson = Regex("""const\s+initial\s*=\s*(\{.*?\})\s*,\s*autoNext""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""const\s+initial\s*=\s*(\{.*\});""")
                .find(html)?.groupValues?.get(1)
            ?: return

        val initial = tryParseJson<VPInitialData>(initialJson) ?: return
        val initialLangLabel = if (initial.lang == "dub") "TR Dublaj" else "Orijinal / Altyazılı"

        emitPayload(initial, initialLangLabel, url, subtitleCallback, callback)

        val code = initial.code?.takeIf { it.isNotBlank() } ?: return
        val encodedCode = URLEncoder.encode(code, "UTF-8")
        
        val alternateLang = if (initial.lang == "dub") {
            "sub" 
        } else if (initial.hasDub == true) {
            "dub" 
        } else {
            null
        }

        if (alternateLang != null) {
            try {
                val apiUrl = "$mainUrl/you/api.php?action=stream&v=$encodedCode&lang=$alternateLang"
                val altText = app.get(
                    apiUrl,
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                
                val altData = tryParseJson<VPInitialData>(altText)
                if (altData != null) {
                    val altLabel = if (alternateLang == "dub") "TR Dublaj" else "Orijinal / Altyazılı"
                    emitPayload(altData, altLabel, url, subtitleCallback, callback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    private suspend fun emitPayload(
        data: VPInitialData,
        langLabel: String,
        embedReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        data.tracks?.forEach { track ->
            val subUrl = track.file?.takeIf { it.isNotBlank() } ?: return@forEach
            if (track.kind == "captions" || track.kind == "subtitles" || track.kind == null) {
                val label = track.label?.takeIf { it.isNotBlank() } ?: track.lang ?: "Altyazı"
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }

        data.sources?.forEach { src ->
            val streamUrl = src.file?.takeIf { it.isNotBlank() } ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ($langLabel)",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
