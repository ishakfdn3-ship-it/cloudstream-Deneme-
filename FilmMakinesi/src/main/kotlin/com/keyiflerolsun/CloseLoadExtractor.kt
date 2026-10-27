
 // ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private fun getm3uLink(data: String): String {
    val first = Base64.decode(data, Base64.DEFAULT).reversedArray()
    val second = Base64.decode(first, Base64.DEFAULT)
    return second.toString(Charsets.UTF_8).split("|").getOrNull(1)
        ?: throw ErrorLoadingException("m3u link not found")
}

open class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.de"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource = app.get(url, referer = extRef)

        iSource.document.select("track").forEach {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = it.attr("label"),
                    url = fixUrl(it.attr("src"))
                )
            )
        }

        val obfuscatedScript = iSource.document
            .select("script[type=text/javascript]")
            .getOrNull(1)?.data()?.trim()
            ?: throw ErrorLoadingException("player script not found")

        val rawScript = getAndUnpack(obfuscatedScript)

        val data = Regex("""return result\}var .*?=.*?\("(.*?)"\)""")
            .find(rawScript)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("data not found")

        val m3uLink = getm3uLink(data)
        Log.d("Kekik_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}
