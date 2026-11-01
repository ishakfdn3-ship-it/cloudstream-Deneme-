package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HotstreamExtractor : ExtractorApi() {

    override val name = "Hotstream"
    override val mainUrl = "https://hotstream.club"
    override val requiresReferer = true

    private val chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/156.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Embed sayfasının gerçek origin'i (mainUrl yerine url'den türetilir)
            val embedUri = URI(url)
            val origin = "${embedUri.scheme}://${embedUri.host}"

            // Embed sayfasını da oynatıcıyla aynı User-Agent ile aç;
            // token/çerez UA'ya bağlıysa uyuşmazlık 403 verir.
            val response = app.get(
                url,
                referer = referer ?: "https://ufilmizle.com/",
                headers = mapOf(
                    "User-Agent" to chromeUserAgent,
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                )
            )

            Log.d("UFILM", "Hotstream embed HTTP: ${response.code}")

            if (response.code !in 200..299) {
                Log.e("UFILM", "Hotstream sayfası açılamadı")
                return
            }

            val html = response.text

            val playerCall = Regex(
                """bePlayer\s*\(\s*('(?:\\.|[^'\\])*')\s*,\s*('(?:\\.|[^'\\])*')""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)

            if (playerCall == null) {
                Log.e("UFILM", "bePlayer parametreleri bulunamadı")
                return
            }

            val hash = JSONTokener(playerCall.groupValues[1])
                .nextValue() as? String

            val encryptedJson = JSONTokener(playerCall.groupValues[2])
                .nextValue() as? String

            if (hash.isNullOrBlank() || encryptedJson.isNullOrBlank()) {
                Log.e("UFILM", "Hotstream parametreleri okunamadı")
                return
            }

            val params = JSONObject(encryptedJson)

            val ciphertext = Base64.decode(
                params.getString("ct"),
                Base64.DEFAULT
            )

            val salt = hexToBytes(params.getString("s"))

            val keyAndIv = deriveKeyAndIv(
                hash.toByteArray(Charsets.UTF_8),
                salt
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    keyAndIv.copyOfRange(0, 32),
                    "AES"
                ),
                IvParameterSpec(
                    keyAndIv.copyOfRange(32, 48)
                )
            )

            val decrypted = cipher.doFinal(ciphertext)
                .toString(Charsets.UTF_8)

            val settings = JSONObject(decrypted)

            val rawVideo = settings
                .optString("video_location")
                .trim()
                // JSON'dan kalan kaçışlı eğik çizgileri temizle
                .replace("\\/", "/")

            if (rawVideo.isBlank()) {
                Log.e("UFILM", "video_location alanı boş")
                return
            }

            val videoUrl = embedUri.resolve(rawVideo).toString()
            val scheme = URI(videoUrl).scheme?.lowercase()

            Log.d("UFILM", "rawVideo: $rawVideo")
            Log.d("UFILM", "videoUrl: $videoUrl")

            if (scheme != "http" && scheme != "https") {
                Log.e("UFILM", "Desteklenmeyen video adresi")
                return
            }

            // Embed sayfasının oturum çerezlerini oynatıcı isteğine ekle.
            val cookieHeader = response.cookies.entries
                .joinToString("; ") { "${it.key}=${it.value}" }

            val playerHeaders = mutableMapOf(
                "User-Agent" to chromeUserAgent,
                "Origin" to origin,
                "Referer" to "$origin/",
                "Accept" to "*/*"
            )

            if (cookieHeader.isNotBlank()) {
                playerHeaders["Cookie"] = cookieHeader
            }

            Log.d("UFILM", "Çerez sayısı: ${response.cookies.size}")

            // Teşhis: aynı başlıklarla m3u8'e istek at (başarısız olursa akışı bozma).
            try {
                val test = app.get(videoUrl, headers = playerHeaders)
                Log.d("UFILM", "m3u8 test HTTP: ${test.code} -> $videoUrl")

                // Başlıklar kabul edilmediyse Referer'ı tam embed URL'si ile dene.
                if (test.code == 403) {
                    val altHeaders = playerHeaders.toMutableMap()
                    altHeaders["Referer"] = url
                    val alt = app.get(videoUrl, headers = altHeaders)
                    Log.d("UFILM", "m3u8 test (Referer=embed url) HTTP: ${alt.code}")

                    if (alt.code in 200..299) {
                        playerHeaders["Referer"] = url
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UFILM", "m3u8 test isteği başarısız", e)
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerHeaders["Referer"] ?: "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = playerHeaders
                }
            )

            Log.d("UFILM", "Hotstream bağlantısı oynatıcıya gönderildi")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UFILM", "Hotstream çözücü hatası", e)
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) {
            "Geçersiz salt uzunluğu"
        }

        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
    }

    private fun deriveKeyAndIv(
        password: ByteArray,
        salt: ByteArray
    ): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val output = mutableListOf<Byte>()
        var previous = ByteArray(0)

        while (output.size < 48) {
            digest.reset()
            digest.update(previous)
            digest.update(password)
            digest.update(salt)

            previous = digest.digest()
            output.addAll(previous.toList())
        }

        return output.take(48).toByteArray()
    }
}
