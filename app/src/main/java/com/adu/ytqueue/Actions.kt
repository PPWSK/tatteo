package com.adu.ytqueue

import kotlinx.coroutines.launch

/** Shared actions used by the main screen and the share sheet. */
object Actions {
    private var syncing = false

    fun addVideo(list: VList, id: String, pos: Pos): Video {
        val existing = Store.lists.firstNotNullOfOrNull { it.find(id) }
        val v = list.find(id) ?: Video(
            id = id,
            title = existing?.title ?: Video.LOADING,
            channel = existing?.channel ?: "",
            duration = existing?.duration ?: 0,
        )
        Store.addVideos(list, listOf(v), pos)
        if (v.title == Video.LOADING || v.title.isBlank()) {
            App.scope.launch {
                val info = YtFetch.videoInfo(id)
                Store.setTitle(id, info?.first?.takeIf { it.isNotBlank() } ?: "YouTube video", info?.second ?: "")
            }
        }
        return list.find(id) ?: v
    }

    /** Imports a playlist into [list] and links it so new videos keep arriving. */
    fun importPlaylist(list: VList, listId: String, onDone: (() -> Unit)? = null) {
        if (YtLinks.isPrivateList(listId)) {
            App.toast("Watch Later and Liked videos are private on YouTube. See menu, About Watch Later.", true)
            return
        }
        val existing = list.links.firstOrNull { it.id == listId }
        val link = existing ?: PlaylistLink(listId, "YouTube playlist").also { list.links += it; Store.changed() }
        App.toast("Reading playlist…")
        App.scope.launch {
            try {
                val d = YtFetch.playlist(listId)
                link.title = d.title
                link.count = d.videos.size
                link.lastSync = System.currentTimeMillis()
                val n = Store.addVideos(list, d.videos, Pos.END, fromSync = true)
                App.toast("${d.title}: $n videos added to ${list.name}", true)
            } catch (e: Exception) {
                if (existing == null) { list.links.remove(link); Store.changed() }
                App.toast("Couldn't read that playlist: ${e.message}", true)
            }
            onDone?.invoke()
        }
    }

    /** Pulls new videos from every linked playlist. Without [force], only links not synced in the last 20 minutes. */
    fun syncAll(force: Boolean, onDone: ((Int) -> Unit)? = null) {
        if (syncing) return
        syncing = true
        App.scope.launch {
            var total = 0
            val errors = mutableListOf<String>()
            for (list in Store.lists.toList()) {
                for (link in list.links.toList()) {
                    if (!force && System.currentTimeMillis() - link.lastSync < 20 * 60_000L) continue
                    try {
                        val d = YtFetch.playlist(link.id)
                        link.title = d.title
                        link.count = d.videos.size
                        link.lastSync = System.currentTimeMillis()
                        total += Store.addVideos(list, d.videos, Pos.END, fromSync = true)
                    } catch (e: Exception) {
                        errors += "${link.title}: ${e.message}"
                    }
                }
            }
            syncing = false
            Store.changed()
            if (force && errors.isNotEmpty()) App.toast(errors.joinToString("\n"), true)
            onDone?.invoke(total)
        }
    }
}
