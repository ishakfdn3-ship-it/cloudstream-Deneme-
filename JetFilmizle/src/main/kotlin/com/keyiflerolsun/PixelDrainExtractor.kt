// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pixelId = Regex("""([^/?]+)(?:\?download)?$""")
            .find(url.substringBefore("#"))
            ?.groupValues
            ?.get(1)
            ?: run {
                Log.e("Kekik_${name}", "PixelDrain dosya ID'si bulunamadı: $url")
                return
            }

        val downloadLink = "$mainUrl/api/file/$pixelId?download"
        val fileReferer = "$mainUrl/u/$pixelId"

        Log.d("Kekik_${name}", "downloadLink » $downloadLink")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "PixelDrain",
                url = downloadLink,
                type = INFER_TYPE
            ) {
                this.referer = fileReferer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
