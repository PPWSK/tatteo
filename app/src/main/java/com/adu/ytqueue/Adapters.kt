package com.adu.ytqueue

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class VideoAdapter(
    private val onClick: (Video) -> Unit,
    private val onMore: (View, Video) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    val items = mutableListOf<Video>()
    var history = false
        private set
    var playingId: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.rowRoot)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val prog: ProgressBar = v.findViewById(R.id.prog)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    fun submit(list: List<Video>, history: Boolean) {
        items.clear()
        items.addAll(list)
        this.history = history
        notifyDataSetChanged()
    }

    fun progressChanged(id: String?) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i, "progress")
    }

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindProgress(h, items[position]) else onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        h.thumb.load(YtLinks.thumb(v.id)) { crossfade(true) }
        h.title.text = v.title
        val playing = v.id == playingId && !history
        h.root.setBackgroundColor(
            if (playing) ContextCompat.getColor(h.root.context, R.color.playing_bg) else 0
        )
        h.title.setTextColor(
            ContextCompat.getColor(h.root.context, if (playing) R.color.accent else R.color.text)
        )
        h.root.setOnClickListener { onClick(v) }
        h.more.setOnClickListener { onMore(it, v) }
        bindProgress(h, v)
    }

    private fun bindProgress(h: VH, v: Video) {
        h.dur.text = if (v.duration > 0) YtLinks.fmt(v.duration) else ""
        h.dur.visibility = if (v.duration > 0) View.VISIBLE else View.GONE
        val showProg = !history && v.position > 5 && v.duration > 0
        h.prog.visibility = if (showProg) View.VISIBLE else View.INVISIBLE
        h.prog.progress = (v.progress * 1000).toInt()

        val parts = mutableListOf<String>()
        if (v.channel.isNotBlank()) parts += v.channel
        if (history) {
            parts += "watched " + DateUtils.getRelativeTimeSpanString(v.watchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } else {
            if (v.position > 5) {
                val left = if (v.duration > 0) "  ·  ${YtLinks.fmt(v.duration - v.position)} left" else ""
                parts += "at ${YtLinks.fmt(v.position)}$left"
            }
            if (v.error != 0) parts += "can't play inside the app, open in YouTube"
        }
        h.sub.text = parts.joinToString("  ·  ")
    }
}

class LinkAdapter(
    private val onAdd: () -> Unit,
    private val onMore: (View, PlaylistLink) -> Unit,
) : RecyclerView.Adapter<LinkAdapter.VH>() {

    private val links = mutableListOf<PlaylistLink>()
    private var listName = ""

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val more: ImageButton = v.findViewById(R.id.more)
        val icon: ImageView = v.findViewById(R.id.icon)
    }

    fun submit(list: VList) {
        links.clear()
        links.addAll(list.links)
        listName = list.name
        notifyDataSetChanged()
    }

    override fun getItemCount() = links.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_link, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        if (position == 0) {
            h.icon.setImageResource(R.drawable.ic_add)
            h.title.text = "Link a YouTube playlist"
            h.sub.text = "Its videos go into $listName, and new ones arrive every time it syncs. The playlist must be Public or Unlisted."
            h.more.visibility = View.GONE
            h.itemView.setOnClickListener { onAdd() }
            return
        }
        val l = links[position - 1]
        h.icon.setImageResource(R.drawable.ic_playlist)
        h.title.text = l.title
        val synced = if (l.lastSync > 0)
            "synced " + DateUtils.getRelativeTimeSpanString(l.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        else "not synced yet"
        h.sub.text = "${l.count} videos  ·  $synced"
        h.more.visibility = View.VISIBLE
        h.more.setOnClickListener { onMore(it, l) }
        h.itemView.setOnClickListener { onMore(h.more, l) }
    }
}
