// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

open class TRsTX : ExtractorApi() {
    override val name = "TRsTX"
    override val mainUrl = "https://trstx.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""

        val videoReq = app.get(url, referer = extRef).text

        val file = Regex("""file":"([^"]+)""")
            .find(videoReq)
            ?.groupValues
            ?.get(1)
            ?: throw ErrorLoadingException("File not found")

        val postLink = "${mainUrl}/" + file.replace("\\", "")

        val rawList = app.post(postLink, referer = extRef)
            .parsedSafe<List<Any>>()
            ?: throw ErrorLoadingException("Post link not found")

        val postJson: List<TrstxVideoData> = rawList.drop(1).mapNotNull { item ->
            val mapItem = item as? Map<*, *> ?: return@mapNotNull null

            TrstxVideoData(
                title = mapItem["title"] as? String,
                file = mapItem["file"] as? String
            )
        }

        Log.d("Kekik_${this.name}", "postJson » $postJson")

        val vidLinks = mutableSetOf<String>()
        val vidMap = mutableListOf<Map<String, String>>()

        for (item in postJson) {
            val itemFile = item.file ?: continue
            val itemTitle = item.title ?: continue

            if (itemFile.isEmpty()) continue

            val fileUrl = "${mainUrl}/playlist/${itemFile.substring(1)}.txt"
            val videoData = app.post(fileUrl, referer = extRef).text

            if (videoData in vidLinks) continue

            vidLinks.add(videoData)
            vidMap.add(
                mapOf(
                    "title" to itemTitle,
                    "videoData" to videoData
                )
            )
        }

        for (mapEntry in vidMap) {
            Log.d("Kekik_${this.name}", "mapEntry » $mapEntry")

            val title = mapEntry["title"] ?: continue
            val m3uLink = mapEntry["videoData"] ?: continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $title",
                    url = m3uLink,
                    type = INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    data class TrstxVideoData(
        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("file")
        val file: String? = null
    )
}
