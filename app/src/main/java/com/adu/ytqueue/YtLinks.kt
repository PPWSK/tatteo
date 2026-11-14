package com.adu.ytqueue

data class YtLink(val videoId: String?, val listId: String?)

object YtLinks {
    private val urlRe = Regex("""https?://\S+""")
    private val videoRe = Regex("""(?:youtu\.be/|[?&]v=|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})""")
    private val listRe = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
    private val bareId = Regex("""^[A-Za-z0-9_-]{11}$""")

    const val WATCH_LATER_HELP =
        "YouTube keeps Watch Later private, so no app can read it, not even with your login.\n\n" +
            "Two ways around it:\n\n" +
            "1. From now on, instead of Save to Watch Later, tap Share and choose Add to YT Queue. You pick the list right there.\n\n" +
            "2. For the videos already in your Watch Later: on youtube.com (computer), open Watch Later, use the three-dot menu at the top of the playlist and choose \"Add all to…\" a new playlist. Set that playlist to Unlisted, then link it here (menu, Link a YouTube playlist). " +
            "Anything you save to that playlist later appears here automatically."

    fun parse(text: String): YtLink? {
        val t = text.trim()
        if (bareId.matches(t)) return YtLink(t, null)
        val url = urlRe.findAll(t).map { it.value }.firstOrNull { it.contains("youtu") } ?: t
        if (!url.contains("youtu")) return null
        val v = videoRe.find(url)?.groupValues?.get(1)
        val l = listRe.find(url)?.groupValues?.get(1)
        if (v == null && l == null) return null
        return YtLink(v, l)
    }

    /** Watch Later and Liked are private to the account and can't be read. */
    fun isPrivateList(id: String) = id == "WL" || id == "LL"

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"
    fun thumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun fmt(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }
}
