package com.adu.ytqueue

/** Connects the player (in MainActivity) with the background service and its notification. */
object PlayerBus {
    interface Controller {
        fun play()
        fun pause()
        fun next()
        fun seekBy(seconds: Int)
        fun close()
    }

    var controller: Controller? = null
    var playing = false
        private set
    var videoId: String? = null
        private set
    var title = ""
        private set
    var subtitle = ""
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun setPlaying(p: Boolean) {
        if (p == playing) return
        playing = p
        emit()
    }

    fun setMeta(id: String?, title: String, subtitle: String) {
        if (id == videoId && title == this.title && subtitle == this.subtitle) return
        videoId = id
        this.title = title
        this.subtitle = subtitle
        emit()
    }

    private fun emit() = listeners.toList().forEach { it() }
}
