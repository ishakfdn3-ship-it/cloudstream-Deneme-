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
            val response = app.get(
                url,
                referer = referer ?: "https://ufilmizle.com/"
            )

            Log.d("UFILM", "Hotstream embed HTTP: ${response.code}")

            if (response.code !in 200..299) {
                Log.e("UFILM", "Hotstream sayfası açılamadı")
                return
            }

            val html = response.text

            // Beklenen biçim:
            // bePlayer('hash', '{"ct":"...","iv":"...","s":"..."}')
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

            if (rawVideo.isBlank()) {
                Log.e("UFILM", "video_location alanı boş")
                return
            }

            val videoUrl = URI(url).resolve(rawVideo).toString()
            val scheme = URI(videoUrl).scheme?.lowercase()

            if (scheme != "http" && scheme != "https") {
                Log.e("UFILM", "Desteklenmeyen video adresi")
                return
            }

            // Teşhis: oynatma listesi ağ istemcisiyle açılıyor mu?
            try {
                val test = app.get(
                    videoUrl,
                    referer = url,
                    headers = mapOf(
                        "User-Agent" to chromeUserAgent
                    )
                )

                Log.d(
                    "UFILM",
                    "Oynatma listesi HTTP: ${test.code}"
                )

                val isHls = test.text
                    .trimStart {
                        it.isWhitespace() || it.code == 65279
                    }
                    .startsWith("#EXTM3U")

                Log.d(
                    "UFILM",
                    "Oynatma listesi HLS mi: $isHls"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "UFILM",
                    "Oynatma listesi test isteği başarısız: " +
                        e.javaClass.simpleName
                )
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to chromeUserAgent
                    )
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
