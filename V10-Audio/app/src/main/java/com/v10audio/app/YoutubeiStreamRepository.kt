/*
 * V10 Audio — InnerTube stream resolver
 *
 * Inspired by public InnerTube client implementations, including:
 * https://github.com/rukamori/morideobfuscator
 * https://github.com/z-huang/InnerTune
 *
 * This is an independent, minimal implementation; it does not copy their resolver.
 * InnerTube is an undocumented interface and may change without notice.
 */
package com.v10audio.app

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class YoutubeAudioStream(
    val videoId: String,
    val title: String,
    val artist: String,
    val streamUrl: String,
    val mimeType: String
)

class YoutubeiStreamRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolves a public YouTube video/music URL to an audio URL when the player response
     * exposes a directly playable format. Ciphered formats and token-gated responses
     * are rejected clearly rather than returning a URL that cannot play.
     */
    fun resolve(input: String): YoutubeAudioStream {
        val videoId = extractVideoId(input)
            ?: throw IOException("Paste a valid YouTube video or music.youtube.com link.")
        val body = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", "ANDROID_VR")
                    .put("clientVersion", "1.60.19")
                    .put("androidSdkVersion", 34)
                    .put("hl", "en")
                    .put("gl", "US")
                    .put("utcOffsetMinutes", 0)))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", "com.google.android.youtube/19.29.1 (Linux; U; Android 14)")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = response.use {
            if (!it.isSuccessful) throw IOException("YouTube returned HTTP ${it.code}. Try again later.")
            JSONObject(it.body?.string() ?: throw IOException("YouTube returned an empty response."))
        }
        val status = json.optJSONObject("playabilityStatus")
        if (status?.optString("status") != "OK") {
            val reason = status?.optJSONObject("errorScreen")
                ?.optJSONObject("playerErrorMessageRenderer")
                ?.optJSONObject("reason")
                ?.optString("simpleText")
                ?: status?.optString("reason")
                ?: "This video is unavailable or needs a supported playback token."
            throw IOException(reason)
        }

        val details = json.optJSONObject("videoDetails")
        val streaming = json.optJSONObject("streamingData")
            ?: throw IOException("No audio stream was returned for this video.")
        val formats = streaming.optJSONArray("adaptiveFormats")
            ?: throw IOException("YouTube returned no adaptive audio formats.")

        var chosenUrl: String? = null
        var chosenMime = "audio/mp4"
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            // Signature-cipher URLs need the upstream JavaScript decipher runtime.
            val url = format.optString("url").takeIf { it.startsWith("https://") } ?: continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                chosenUrl = url
                chosenMime = mime.substringBefore(';')
            }
        }
        val stream = chosenUrl ?: throw IOException(
            "YouTube returned protected/ciphered formats. This source needs the full JavaScript decipher and token runtime; a direct URL alone cannot play it."
        )
        return YoutubeAudioStream(
            videoId = videoId,
            title = details?.optString("title")?.takeIf(String::isNotBlank) ?: "YouTube track",
            artist = details?.optString("author")?.takeIf(String::isNotBlank) ?: "YouTube",
            streamUrl = stream,
            mimeType = chosenMime
        )
    }

    private fun extractVideoId(input: String): String? {
        val value = input.trim()
        if (value.matches(Regex("[A-Za-z0-9_-]{11}"))) return value
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val id = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> uri.lastPathSegment
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                uri.path == "/watch" -> uri.getQueryParameter("v")
                uri.pathSegments.firstOrNull() in setOf("shorts", "embed", "live") -> uri.pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
}
