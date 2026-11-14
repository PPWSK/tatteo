package com.adu.ytqueue

import org.json.JSONArray
import org.json.JSONObject

enum class Pos { TOP, NEXT, END }

class Video(
    val id: String,
    var title: String,
    var channel: String = "",
    var duration: Int = 0,
    var position: Int = 0,
    var watched: Boolean = false,
    var addedAt: Long = System.currentTimeMillis(),
    var watchedAt: Long = 0,
    var error: Int = 0,
) {
    val progress: Float
        get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", title).put("c", channel).put("d", duration)
        .put("p", position).put("w", watched).put("a", addedAt).put("wa", watchedAt).put("e", error)

    companion object {
        const val LOADING = "Loading title…"

        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"),
            title = o.optString("t"),
            channel = o.optString("c"),
            duration = o.optInt("d"),
            position = o.optInt("p"),
            watched = o.optBoolean("w"),
            addedAt = o.optLong("a"),
            watchedAt = o.optLong("wa"),
            error = o.optInt("e"),
        )
    }
}

class PlaylistLink(
    val id: String,
    var title: String,
    var lastSync: Long = 0,
    var count: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("s", lastSync).put("n", count)

    companion object {
        fun fromJson(o: JSONObject) = PlaylistLink(o.getString("id"), o.optString("t"), o.optLong("s"), o.optInt("n"))
    }
}

class VList(val id: String, var name: String) {
    val items = mutableListOf<Video>()
    val links = mutableListOf<PlaylistLink>()
    val removed = mutableSetOf<String>()

    fun queue(): List<Video> = items.filter { !it.watched }
    fun history(): List<Video> = items.filter { it.watched }.sortedByDescending { it.watchedAt }
    fun find(videoId: String?): Video? = items.firstOrNull { it.id == videoId }

    fun toJson(): JSONObject {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        val ln = JSONArray(); links.forEach { ln.put(it.toJson()) }
        val rm = JSONArray(); removed.forEach { rm.put(it) }
        return JSONObject().put("id", id).put("name", name).put("items", arr).put("links", ln).put("removed", rm)
    }

    companion object {
        fun fromJson(o: JSONObject): VList {
            val l = VList(o.getString("id"), o.optString("name", "List"))
            o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) l.items += Video.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("links")?.let { a -> for (i in 0 until a.length()) l.links += PlaylistLink.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("removed")?.let { a -> for (i in 0 until a.length()) l.removed += a.getString(i) }
            return l
        }
    }
}
