
 // ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class ContentX : ExtractorApi() {
    override val name = "ContentX"
    override val mainUrl = "https://contentx.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""

        Log.d("Kekik_${this.name}", "url » $url")

        val iSource = app.get(url, referer = extRef).text

        val iExtract = Regex("""window\.openPlayer\('([^']+)'""")
            .find(iSource)?.groups?.get(1)?.value
            ?: throw ErrorLoadingException("iExtract is null")

        val subUrls = mutableSetOf<String>()

        Regex(""""file":"([^"]+)","label":"([^"]+)"""")
            .findAll(iSource)
            .forEach {
                val (subUrl, subLang) = it.destructured

                if (!subUrls.add(subUrl)) return@forEach

                subtitleCallback(
                    newSubtitleFile(
                        lang = subLang
                            .replace("\\u0131", "ı")
                            .replace("\\u0130", "İ")
                            .replace("\\u00fc", "ü")
                            .replace("\\u00e7", "ç"),
                        url = fixUrl(subUrl.replace("\\", ""))
                    )
                )
            }

        val vidSource = app.get(
            "$mainUrl/source2.php?v=$iExtract",
            referer = extRef
        ).text

        val vidExtract = Regex("""file":"([^"]+)""")
            .find(vidSource)?.groups?.get(1)?.value
            ?: throw ErrorLoadingException("vidExtract is null")

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = vidExtract.replace("\\", ""),
                type = ExtractorLinkType.M3U8
            )
        )

        val iDublaj = Regex(""","([^']+)","Türkçe""")
            .find(iSource)?.groups?.get(1)?.value

        if (iDublaj != null) {
            val dublajSource = app.get(
                "$mainUrl/source2.php?v=$iDublaj",
                referer = extRef
            ).text

            val dublajExtract = Regex("""file":"([^"]+)""")
                .find(dublajSource)?.groups?.get(1)?.value
                ?: throw ErrorLoadingException("dublajExtract is null")

            callback(
                newExtractorLink(
                    source = "$name Türkçe Dublaj",
                    name = "$name Türkçe Dublaj",
                    url = dublajExtract.replace("\\", ""),
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }
              }
              
