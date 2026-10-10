/*
 * V10 Audio — independent YouTube Music InnerTube search client.
 *
 * InnerTube is an undocumented interface and may change without notice.
 * Search metadata is parsed defensively; unavailable fields receive safe defaults.
 */
package com.v10audio.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OnlineTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null
) {
    val watchUrl: String get() = "https://music.youtube.com/watch?v=$videoId"
}

class YoutubeiSearchRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun search(query: String): List<OnlineTrack> {
        if (query.isBlank()) return emptyList()
        val context = JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250616.03.00")
            .put("hl", "en")
            .put("gl", "US"))
        val body = JSONObject().put("context", context)
            .put("query", query.trim())
            .put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Content-Type", "application/json")
            .post(body).build()
        val root = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music search failed (HTTP ${response.code}).")
            JSONObject(response.body?.string() ?: throw IOException("YouTube Music returned an empty response."))
        }
        val results = mutableListOf<OnlineTrack>()
        collect(root, results)
        return results.distinctBy { it.videoId }.take(30)
    }

    private fun collect(node: Any?, out: MutableList<OnlineTrack>) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) parseResponsive(renderer)?.let(out::add)
                val twoRow = node.optJSONObject("musicTwoRowItemRenderer")
                if (twoRow != null) parseTwoRow(twoRow)?.let(out::add)
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun parseResponsive(r: JSONObject): OnlineTrack? {
        val videoId = findVideoId(r) ?: return null
        val columns = r.optJSONArray("flexColumns") ?: JSONArray()
        val title = columnText(columns.optJSONObject(0)).ifBlank { "Unknown track" }
        val subtitle = columnText(columns.optJSONObject(1))
        val artist = subtitle.split(" • ", " · ", " - ").firstOrNull().orEmpty().ifBlank { "YouTube Music" }
        val album = subtitle.split(" • ", " · ").drop(1).firstOrNull().orEmpty()
        val thumb = firstThumbnail(r)
        val duration = findDuration(r)
        return OnlineTrack(videoId, title, artist, album, thumb, duration)
    }

    private fun parseTwoRow(r: JSONObject): OnlineTrack? {
        val videoId = findVideoId(r) ?: return null
        val title = textValue(r.optJSONObject("title")).ifBlank { "Unknown track" }
        val subtitle = textValue(r.optJSONObject("subtitle"))
        return OnlineTrack(videoId, title, subtitle.ifBlank { "YouTube Music" }, thumbnailUrl = firstThumbnail(r))
    }

    private fun findVideoId(node: JSONObject): String? {
        node.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        node.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            ?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        val endpoint = node.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        endpoint?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        val keys = node.keys()
        while (keys.hasNext()) {
            val child = node.opt(keys.next())
            if (child is JSONObject) findVideoId(child)?.let { return it }
            if (child is JSONArray) for (i in 0 until child.length()) {
                val item = child.opt(i)
                if (item is JSONObject) findVideoId(item)?.let { return it }
            }
        }
        return null
    }

    private fun columnText(column: JSONObject?): String {
        val renderer = column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer") ?: return ""
        return textValue(renderer.optJSONObject("text"))
    }

    private fun textValue(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }
            .joinToString("").trim()
    }

    private fun firstThumbnail(node: JSONObject): String? {
        val thumbs = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("https://") }
    }

    private fun findDuration(node: JSONObject): String? {
        node.optJSONObject("fixedColumns")?.let { fixed ->
            val keys = fixed.keys()
            while (keys.hasNext()) {
                val item = fixed.optJSONObject(keys.next()) ?: continue
                val value = textValue(item.optJSONObject("text"))
                if (value.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?"))) return value
            }
        }
        return null
    }
}
