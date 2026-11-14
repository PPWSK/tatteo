package com.adu.ytqueue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Reads public YouTube data without an API key: oEmbed for single videos, the playlist page for playlists. */
object YtFetch {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class PlaylistData(val title: String, val videos: List<Video>)

    private fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        // Skips the EU cookie-consent interstitial.
        c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        if (code !in 200..299) throw IOException("YouTube answered HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun videoInfo(id: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val u = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(YtLinks.watchUrl(id), "UTF-8")
            val o = JSONObject(request(u))
            o.optString("title") to o.optString("author_name")
        }.getOrNull()
    }

    suspend fun playlist(listId: String): PlaylistData = withContext(Dispatchers.IO) {
        val html = request("https://www.youtube.com/playlist?list=$listId&hl=en")
        val dataStr = extractJson(html, "var ytInitialData = ")
            ?: extractJson(html, "ytInitialData\"] = ")
            ?: extractJson(html, "ytInitialData")
            ?: throw IOException("Couldn't read the YouTube page")
        val data = JSONObject(dataStr)

        val videos = LinkedHashMap<String, Video>()
        val lockups = LinkedHashMap<String, Video>()
        val tokens = mutableListOf<String>()
        walk(data, videos, lockups, tokens)

        val title = findFirst(data, "playlistMetadataRenderer")?.optString("title")?.takeIf { it.isNotBlank() }
            ?: findFirst(data, "pageHeaderViewModel")?.let { textOf(it.optJSONObject("title")) }
            ?: "YouTube playlist"

        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "2.20250101.00.00"
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)

        val seen = HashSet<String>()
        var pages = 0
        while (tokens.isNotEmpty() && pages < 60) {
            val token = tokens.removeAt(0)
            if (!seen.add(token)) continue
            pages++
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", version).put("hl", "en")))
                .put("continuation", token)
            val url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false" + (key?.let { "&key=$it" } ?: "")
            val resp = runCatching {
                JSONObject(
                    request(
                        url, body.toString(),
                        mapOf("X-YouTube-Client-Name" to "1", "X-YouTube-Client-Version" to version, "Origin" to "https://www.youtube.com")
                    )
                )
            }.getOrNull() ?: break
            walk(resp, videos, lockups, tokens)
        }

        val result = if (videos.isNotEmpty()) videos.values.toList() else lockups.values.toList()
        if (result.isEmpty()) {
            val alert = findFirst(data, "alertRenderer")?.let { textOf(it.optJSONObject("text")) }
                ?: findFirst(data, "alertWithButtonRenderer")?.let { textOf(it.optJSONObject("text")) }
            throw IOException(alert ?: "No videos found. If the playlist is Private, set it to Unlisted.")
        }
        PlaylistData(title, result)
    }

    private fun walk(n: Any?, out: MutableMap<String, Video>, lockups: MutableMap<String, Video>, tokens: MutableList<String>) {
        when (n) {
            is JSONObject -> {
                n.optJSONObject("playlistVideoRenderer")?.let { r ->
                    val id = r.optString("videoId")
                    if (id.length == 11 && r.optBoolean("isPlayable", true) && id !in out) {
                        out[id] = Video(
                            id = id,
                            title = textOf(r.optJSONObject("title")) ?: "Video",
                            channel = textOf(r.optJSONObject("shortBylineText")) ?: "",
                            duration = r.optString("lengthSeconds").toIntOrNull() ?: 0,
                        )
                    }
                }
                n.optJSONObject("lockupViewModel")?.let { r ->
                    val id = r.optString("contentId")
                    if (r.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" && id.length == 11 && id !in lockups) {
                        val t = r.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                            ?.optJSONObject("title")?.optString("content")
                        lockups[id] = Video(id, t ?: "Video")
                    }
                }
                n.optJSONObject("continuationItemRenderer")?.let { r ->
                    findFirst(r, "continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
                        if (it !in tokens) tokens += it
                    }
                }
                val keys = n.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "playlistVideoRenderer" || k == "lockupViewModel" || k == "continuationItemRenderer") continue
                    walk(n.opt(k), out, lockups, tokens)
                }
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), out, lockups, tokens)
        }
    }

    private fun findFirst(n: Any?, key: String): JSONObject? {
        when (n) {
            is JSONObject -> {
                n.optJSONObject(key)?.let { return it }
                val keys = n.keys()
                while (keys.hasNext()) findFirst(n.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until n.length()) findFirst(n.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun textOf(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        o.optJSONObject("dynamicTextViewModel")?.let { textOf(it.optJSONObject("text")) }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /** Finds the JSON object literal that follows [marker] by matching braces. */
    private fun extractJson(html: String, marker: String): String? {
        val i = html.indexOf(marker)
        if (i < 0) return null
        val start = html.indexOf('{', i)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until html.length) {
            val ch = html[j]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
            } else when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, j + 1)
                }
            }
        }
        return null
    }
}
