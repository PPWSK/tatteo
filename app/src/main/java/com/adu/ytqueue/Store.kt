package com.adu.ytqueue

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** All app state, kept in memory and saved to a JSON file. Mutate on the main thread only. */
object Store {
    enum class Change { STRUCTURE, PROGRESS }

    val lists = mutableListOf<VList>()
    var currentListId = ""
    var autoplayNext = true
    var backgroundPlay = true

    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Change, String?) -> Unit>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "state.json")
        if (file.exists()) runCatching { load(JSONObject(file.readText())) }
        if (lists.isEmpty()) {
            lists += VList(newId(), "Watch later")
            lists += VList(newId(), "Portuguese")
        }
        if (lists.none { it.id == currentListId }) currentListId = lists[0].id
    }

    private fun load(o: JSONObject) {
        o.optJSONArray("lists")?.let { a -> for (i in 0 until a.length()) lists += VList.fromJson(a.getJSONObject(i)) }
        currentListId = o.optString("current")
        autoplayNext = o.optBoolean("autoplay", true)
        backgroundPlay = o.optBoolean("bg", true)
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun current(): VList = lists.firstOrNull { it.id == currentListId } ?: lists[0].also { currentListId = it.id }
    fun list(id: String?): VList? = lists.firstOrNull { it.id == id }

    fun addListener(l: (Change, String?) -> Unit) { listeners += l }
    fun removeListener(l: (Change, String?) -> Unit) { listeners -= l }

    fun changed(change: Change = Change.STRUCTURE, id: String? = null) {
        save()
        val run = { listeners.toList().forEach { it(change, id) } }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    fun save() {
        val arr = JSONArray(); lists.forEach { arr.put(it.toJson()) }
        val text = JSONObject().put("lists", arr).put("current", currentListId)
            .put("autoplay", autoplayNext).put("bg", backgroundPlay).toString()
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, "state.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    // ---- list operations ----

    private fun insertIndex(list: VList, pos: Pos): Int = when (pos) {
        Pos.TOP -> 0
        Pos.END -> list.items.size
        Pos.NEXT -> {
            val first = list.items.indexOfFirst { !it.watched }
            if (first < 0) 0 else first + 1
        }
    }

    /** Adds videos; returns how many were new (or brought back from watched). */
    fun addVideos(list: VList, videos: List<Video>, pos: Pos, fromSync: Boolean = false): Int {
        val toInsert = mutableListOf<Video>()
        var added = 0
        for (v in videos) {
            if (toInsert.any { it.id == v.id }) continue
            val existing = list.find(v.id)
            if (existing != null) {
                if (fromSync) continue
                if (existing.watched) {
                    existing.watched = false
                    existing.position = 0
                    added++
                } else if (pos == Pos.END) continue
                list.items.remove(existing)
                toInsert += existing
                continue
            }
            if (fromSync && v.id in list.removed) continue
            list.removed.remove(v.id)
            toInsert += v
            added++
        }
        if (toInsert.isNotEmpty()) list.items.addAll(insertIndex(list, pos), toInsert)
        changed()
        return added
    }

    fun moveTo(list: VList, id: String, pos: Pos) {
        val v = list.find(id) ?: return
        list.items.remove(v)
        v.watched = false
        list.items.add(insertIndex(list, pos), v)
        changed()
    }

    fun markWatched(list: VList, id: String) {
        val v = list.find(id) ?: return
        v.watched = true
        v.watchedAt = System.currentTimeMillis()
        v.position = 0
        changed()
    }

    fun requeue(list: VList, id: String, pos: Pos) {
        val v = list.find(id) ?: return
        v.watched = false
        v.position = 0
        v.error = 0
        list.items.remove(v)
        list.items.add(insertIndex(list, pos), v)
        changed()
    }

    /** Removes a video; returns its old index so it can be restored. */
    fun remove(list: VList, id: String): Int {
        val idx = list.items.indexOfFirst { it.id == id }
        if (idx < 0) return -1
        list.items.removeAt(idx)
        list.removed += id
        changed()
        return idx
    }

    fun restore(list: VList, idx: Int, v: Video) {
        list.removed -= v.id
        if (list.find(v.id) == null) list.items.add(idx.coerceIn(0, list.items.size), v)
        changed()
    }

    /** Reorder within the queue (unwatched items). No UI refresh, the adapter already moved the row. */
    fun moveInQueue(list: VList, from: Int, to: Int) {
        val q = list.queue().toMutableList()
        if (from !in q.indices || to !in q.indices) return
        q.add(to, q.removeAt(from))
        val watched = list.items.filter { it.watched }
        list.items.clear()
        list.items.addAll(q)
        list.items.addAll(watched)
        save()
    }

    fun moveToList(from: VList, id: String, to: VList) {
        val v = from.find(id) ?: return
        from.items.remove(v)
        from.removed += id
        to.removed -= id
        to.find(id)?.let { to.items.remove(it) }
        v.watched = false
        to.items.add(v)
        changed()
    }

    fun clearWatched(list: VList) {
        val w = list.items.filter { it.watched }
        list.removed += w.map { it.id }
        list.items.removeAll(w)
        changed()
    }

    fun updateProgress(listId: String?, id: String, pos: Int, dur: Int) {
        val v = list(listId)?.find(id) ?: return
        v.position = pos
        if (dur > 0) v.duration = dur
        changed(Change.PROGRESS, id)
    }

    fun setTitle(id: String, title: String, channel: String) {
        lists.forEach { l -> l.find(id)?.let { it.title = title; it.channel = channel } }
        changed()
    }

    fun addList(name: String): VList {
        val l = VList(newId(), name)
        lists += l
        changed()
        return l
    }

    fun deleteList(list: VList) {
        if (lists.size <= 1) return
        lists.remove(list)
        if (currentListId == list.id) currentListId = lists[0].id
        changed()
    }
}
