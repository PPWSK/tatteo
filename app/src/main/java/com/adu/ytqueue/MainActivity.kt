package com.adu.ytqueue

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity(), PlayerBus.Controller {
    private enum class Mode { QUEUE, WATCHED, LINKS }

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var playerBox: FrameLayout
    private lateinit var fullBox: FrameLayout
    private lateinit var web: BgWebView
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var tabs: TabLayout
    private lateinit var toggle: MaterialButtonToggleGroup
    private lateinit var btnQueue: MaterialButton
    private lateinit var btnWatched: MaterialButton
    private lateinit var btnLinks: MaterialButton
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView

    private lateinit var adapter: VideoAdapter
    private lateinit var linkAdapter: LinkAdapter
    private var mode = Mode.QUEUE

    private var playerReady = false
    private var pendingAutoplay = false
    private var playingId: String? = null
    private var playingListId: String? = null
    private var isPlaying = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var buildingTabs = false

    private val storeListener: (Store.Change, String?) -> Unit = { change, id ->
        if (change == Store.Change.PROGRESS) {
            if (mode == Mode.QUEUE) adapter.progressChanged(id)
            if (id == playingId) updateNow()
        } else render()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        playerBox = findViewById(R.id.playerBox)
        fullBox = findViewById(R.id.fullBox)
        web = findViewById(R.id.web)
        nowTitle = findViewById(R.id.nowTitle)
        nowSub = findViewById(R.id.nowSub)
        btnPlay = findViewById(R.id.btnPlay)
        tabs = findViewById(R.id.listTabs)
        toggle = findViewById(R.id.toggle)
        btnQueue = findViewById(R.id.btnQueue)
        btnWatched = findViewById(R.id.btnWatched)
        btnLinks = findViewById(R.id.btnLinks)
        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        adapter = VideoAdapter(::onRowClick, ::onRowMore)
        linkAdapter = LinkAdapter(onAdd = { addDialog(linkOnly = true) }, onMore = ::onLinkMore)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        setupTouch()
        setupTabs()

        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = when (id) {
                R.id.btnWatched -> Mode.WATCHED
                R.id.btnLinks -> Mode.LINKS
                else -> Mode.QUEUE
            }
            render()
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { seekBy(-10) }
        findViewById<ImageButton>(R.id.btnFwd).setOnClickListener { seekBy(30) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { next() }
        btnPlay.setOnClickListener { if (isPlaying) pause() else play() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) exitFull() else moveTaskToBack(true)
            }
        })

        Store.addListener(storeListener)
        PlayerBus.controller = this
        setupWeb()
        askNotificationPermission()
        layoutPlayer()
        render()
        updateNow()
    }

    override fun onResume() {
        super.onResume()
        Actions.syncAll(force = false)
    }

    override fun onStop() {
        super.onStop()
        if (!Store.backgroundPlay && !isChangingConfigurations) pause()
    }

    override fun onDestroy() {
        Store.removeListener(storeListener)
        if (PlayerBus.controller === this) PlayerBus.controller = null
        PlayerBus.setPlaying(false)
        PlaybackService.stop(this)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutPlayer()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // ------------------------------------------------------------------ player

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.keepAlive = Store.backgroundPlay
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(JsBridge(), "Android")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFull(view, callback)
            override fun onHideCustomView() = exitFull()
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
                return false
            }
        }
        val html = assets.open("player.html").bufferedReader().use { it.readText() }
        // YouTube requires the embedding page to identify itself; the app id is the documented form.
        web.loadDataWithBaseURL("https://com.adu.ytqueue/", html, "text/html", "utf-8", null)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onReady() = runOnUiThread {
            playerReady = true
            playingId = null
            loadTop(pendingAutoplay)
        }

        @JavascriptInterface
        fun onProgress(t: Double, d: Double) = runOnUiThread { saveProgress(t, d) }

        @JavascriptInterface
        fun onState(state: Int, t: Double, d: Double) = runOnUiThread { onPlayerState(state, t, d) }

        @JavascriptInterface
        fun onError(code: Int) = runOnUiThread { onPlayerError(code) }
    }

    private fun js(code: String) {
        if (playerReady) web.evaluateJavascript(code, null)
    }

    private fun saveProgress(t: Double, d: Double) {
        val id = playingId ?: return
        Store.updateProgress(playingListId, id, t.toInt(), d.toInt())
    }

    private fun onPlayerState(state: Int, t: Double, d: Double) {
        when (state) {
            1 -> { // playing
                isPlaying = true
                saveProgress(t, d)
                playingVideo()?.let { if (it.error != 0) { it.error = 0; Store.changed() } }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Store.backgroundPlay && !PlaybackService.running) PlaybackService.start(this)
            }
            2 -> { // paused
                isPlaying = false
                saveProgress(t, d)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            0 -> { // ended
                isPlaying = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onEnded()
            }
        }
        btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        PlayerBus.setPlaying(isPlaying)
    }

    private fun onEnded() {
        val id = playingId ?: return
        Store.list(playingListId)?.let { Store.markWatched(it, id) }
        playingId = null
        loadTop(Store.autoplayNext)
    }

    private fun onPlayerError(code: Int) {
        val id = playingId ?: return
        val list = Store.list(playingListId) ?: return
        list.find(id)?.error = code
        Store.changed()
        val msg = when (code) {
            101, 150, 152 -> "This video's owner doesn't allow playback outside YouTube"
            100 -> "Video not found, or it's private"
            153 -> "YouTube refused the player (error 153)"
            else -> "Can't play this video (error $code)"
        }
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).setAction("Open in YouTube") { openVideo(id) }.show()
        if (Store.autoplayNext && list.queue().any { it.error == 0 }) {
            playingId = null
            loadTop(true)
        }
    }

    private fun playingVideo(): Video? = Store.list(playingListId)?.find(playingId)

    /** Loads the first playable video of the current list. */
    private fun loadTop(autoplay: Boolean) {
        if (!playerReady) {
            pendingAutoplay = pendingAutoplay || autoplay
            updateNow()
            return
        }
        pendingAutoplay = false
        val list = Store.current()
        val q = list.queue()
        val v = q.firstOrNull { it.error == 0 } ?: q.firstOrNull()
        if (v == null) {
            if (playingId != null) js("stopVideo()")
            playingId = null
            playingListId = list.id
            isPlaying = false
            btnPlay.setImageResource(R.drawable.ic_play)
            PlayerBus.setPlaying(false)
            updateNow()
            refreshPlayingRow()
            return
        }
        playVideo(list, v, autoplay)
    }

    private fun playVideo(list: VList, v: Video, autoplay: Boolean) {
        if (v.id == playingId && list.id == playingListId) {
            if (autoplay) js("play()")
            updateNow()
            return
        }
        playingId = v.id
        playingListId = list.id
        var start = v.position
        if (v.duration > 0 && start >= v.duration - 10) start = 0
        if (start > 3) start -= 3 // a few seconds of context when resuming
        js("load('${v.id}', $start, $autoplay)")
        updateNow()
        refreshPlayingRow()
    }

    private fun refreshPlayingRow() {
        adapter.playingId = playingId
        if (recycler.adapter === adapter) adapter.notifyDataSetChanged()
    }

    private fun updateNow() {
        val list = Store.current()
        val v = playingVideo()
        if (v == null) {
            val first = list.queue().firstOrNull()
            nowTitle.text = first?.title ?: "Nothing queued in ${list.name}"
            nowSub.text = if (first == null) "Share a video from YouTube, or tap + to paste a link" else list.name
            PlayerBus.setMeta(first?.id, first?.title ?: "", list.name)
            return
        }
        nowTitle.text = v.title
        val pos = if (v.duration > 0) "${YtLinks.fmt(v.position)} / ${YtLinks.fmt(v.duration)}" else ""
        nowSub.text = listOf(Store.list(playingListId)?.name ?: "", v.channel, pos).filter { it.isNotBlank() }.joinToString("  ·  ")
        PlayerBus.setMeta(v.id, v.title, listOf(Store.list(playingListId)?.name ?: "", v.channel).filter { it.isNotBlank() }.joinToString(" · "))
    }

    // PlayerBus.Controller (also used by the notification and headphone buttons)

    override fun play() {
        if (playingId == null) loadTop(true) else js("play()")
    }

    override fun pause() = js("pause()")

    override fun next() {
        val id = playingId
        val list = Store.list(playingListId)
        if (id != null && list != null) Store.markWatched(list, id)
        playingId = null
        loadTop(true)
    }

    override fun seekBy(seconds: Int) = js("seekBy($seconds)")

    override fun close() {
        pause()
        PlaybackService.stop(this)
    }

    // ------------------------------------------------------------------ layout & fullscreen

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun layoutPlayer() {
        val land = isLandscape() || customView != null
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i)
            if (c !== playerBox) c.visibility = if (land) View.GONE else View.VISIBLE
        }
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        if (land) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.weight = 0f
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
        }
        playerBox.layoutParams = lp
        content.fitsSystemWindows = !land
        content.requestApplyInsets()
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (land) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            render()
        }
    }

    private fun enterFull(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customCallback = callback
        fullBox.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullBox.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        layoutPlayer()
    }

    private fun exitFull() {
        customView?.let { fullBox.removeView(it) }
        customView = null
        fullBox.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        layoutPlayer()
    }

    // ------------------------------------------------------------------ list tabs

    private fun setupTabs() {
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (buildingTabs) return
                val id = tab.tag as? String ?: return
                if (id == "+") {
                    selectCurrentTab()
                    newListDialog()
                    return
                }
                switchList(id)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.tag == "+") newListDialog()
            }
        })
        buildTabs()
    }

    private fun tabLabel(l: VList): String {
        val n = l.queue().size
        return if (n > 0) "${l.name}  $n" else l.name
    }

    private fun buildTabs() {
        buildingTabs = true
        tabs.removeAllTabs()
        Store.lists.forEach { l ->
            val t = tabs.newTab().setText(tabLabel(l)).setTag(l.id)
            tabs.addTab(t, l.id == Store.currentListId)
            t.view.setOnLongClickListener { v -> listMenu(v, l); true }
        }
        tabs.addTab(tabs.newTab().setText("+").setTag("+"), false)
        buildingTabs = false
    }

    private fun updateTabs() {
        val ids = Store.lists.map { it.id } + "+"
        val tabIds = (0 until tabs.tabCount).map { tabs.getTabAt(it)?.tag as? String }
        if (ids != tabIds) {
            buildTabs()
            return
        }
        Store.lists.forEachIndexed { i, l -> tabs.getTabAt(i)?.setText(tabLabel(l)) }
        selectCurrentTab()
    }

    private fun selectCurrentTab() {
        val idx = Store.lists.indexOfFirst { it.id == Store.currentListId }
        val tab = tabs.getTabAt(idx) ?: return
        if (tabs.selectedTabPosition != idx) {
            buildingTabs = true
            tab.select()
            buildingTabs = false
        }
    }

    private fun switchList(id: String) {
        if (id == Store.currentListId) return
        Store.currentListId = id
        mode = Mode.QUEUE
        toggle.check(R.id.btnQueue)
        Store.changed()
        playingId = null
        loadTop(true) // picks up where this list was left
    }

    private fun listMenu(anchor: View, l: VList) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Rename")
        pm.menu.add(0, 2, 1, "Delete")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> renameDialog(l)
                2 -> deleteDialog(l)
            }
            true
        }
        pm.show()
    }

    private fun newListDialog() = prompt("New list", "", "Create") { name ->
        val l = Store.addList(name.ifBlank { "New list" })
        switchList(l.id)
    }

    private fun renameDialog(l: VList) = prompt("Rename list", l.name, "Save") { name ->
        if (name.isNotBlank()) {
            l.name = name
            Store.changed()
        }
    }

    private fun deleteDialog(l: VList) {
        if (Store.lists.size <= 1) {
            App.toast("You need at least one list")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${l.name}\"?")
            .setMessage("Its queue and history are removed from this app. Nothing changes on YouTube.")
            .setPositiveButton("Delete") { _, _ ->
                val wasPlaying = l.id == playingListId
                Store.deleteList(l)
                if (wasPlaying) {
                    playingId = null
                    loadTop(false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        if (isLandscape() || customView != null) return
        updateTabs()
        val list = Store.current()
        val q = list.queue()
        val h = list.history()
        btnQueue.text = "Up next ${q.size}"
        btnWatched.text = "Watched ${h.size}"
        btnLinks.text = "Playlists ${list.links.size}"
        when (mode) {
            Mode.QUEUE -> {
                if (recycler.adapter !== adapter) recycler.adapter = adapter
                adapter.playingId = playingId
                adapter.submit(q, false)
                empty.text = "Nothing queued in ${list.name}.\n\nIn the YouTube app tap Share, then Add to YT Queue. Or tap + up top to paste a link."
                empty.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            }
            Mode.WATCHED -> {
                if (recycler.adapter !== adapter) recycler.adapter = adapter
                adapter.submit(h, true)
                empty.text = "Videos you finish in ${list.name} show up here."
                empty.visibility = if (h.isEmpty()) View.VISIBLE else View.GONE
            }
            Mode.LINKS -> {
                if (recycler.adapter !== linkAdapter) recycler.adapter = linkAdapter
                linkAdapter.submit(list)
                empty.visibility = View.GONE
            }
        }
        updateNow()
    }

    // ------------------------------------------------------------------ rows

    private fun onRowClick(v: Video) {
        val list = Store.current()
        if (adapter.history) {
            onRowMoreFor(recycler.findViewHolderForAdapterPosition(adapter.items.indexOf(v))?.itemView ?: recycler, v)
            return
        }
        if (v.id == playingId && list.id == playingListId) {
            if (isPlaying) pause() else play()
            return
        }
        v.error = 0
        Store.moveTo(list, v.id, Pos.TOP)
        playVideo(list, v, true)
    }

    private fun onRowMore(anchor: View, v: Video) = onRowMoreFor(anchor, v)

    private fun onRowMoreFor(anchor: View, v: Video) {
        val list = Store.current()
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        if (!v.watched) {
            m.add(0, 1, 0, "Play now")
            m.add(0, 2, 1, "Play next")
            m.add(0, 3, 2, "Move to bottom")
            m.add(0, 4, 3, "Mark as watched")
            if (v.position > 0) m.add(0, 5, 4, "Start from the beginning")
            if (Store.lists.size > 1) m.add(0, 6, 5, "Move to another list…")
        } else {
            m.add(0, 10, 0, "Watch again now")
            m.add(0, 11, 1, "Put back in the queue")
        }
        m.add(0, 20, 20, "Open in YouTube")
        m.add(0, 21, 21, "Remove")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> onRowClick(v)
                2 -> Store.moveTo(list, v.id, Pos.NEXT)
                3 -> Store.moveTo(list, v.id, Pos.END)
                4 -> if (v.id == playingId) next() else Store.markWatched(list, v.id)
                5 -> {
                    v.position = 0
                    Store.changed()
                    if (v.id == playingId) js("seekTo(0)")
                }
                6 -> moveToListDialog(list, v)
                10 -> {
                    Store.requeue(list, v.id, Pos.TOP)
                    toggle.check(R.id.btnQueue)
                    playVideo(list, v, true)
                }
                11 -> Store.requeue(list, v.id, Pos.END)
                20 -> openVideo(v.id)
                21 -> removeWithUndo(list, v)
            }
            true
        }
        pm.show()
    }

    private fun moveToListDialog(from: VList, v: Video) {
        val others = Store.lists.filter { it.id != from.id }
        MaterialAlertDialogBuilder(this)
            .setTitle("Move to…")
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val wasPlaying = v.id == playingId
                Store.moveToList(from, v.id, others[which])
                if (wasPlaying) {
                    playingId = null
                    loadTop(isPlaying)
                }
                App.toast("Moved to ${others[which].name}")
            }
            .show()
    }

    private fun removeWithUndo(list: VList, v: Video) {
        val wasPlaying = v.id == playingId
        val idx = Store.remove(list, v.id)
        if (idx < 0) return
        if (wasPlaying) {
            playingId = null
            loadTop(isPlaying)
        }
        Snackbar.make(root, "Removed", Snackbar.LENGTH_LONG).setAction("Undo") { Store.restore(list, idx, v) }.show()
    }

    private fun setupTouch() {
        val cb = object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (rv.adapter !== adapter) return 0
                val drag = if (mode == Mode.QUEUE) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
                return makeMovementFlags(drag, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
            }

            override fun isLongPressDragEnabled() = mode == Mode.QUEUE

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                adapter.move(from, to)
                Store.moveInQueue(Store.current(), from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos < 0 || pos >= adapter.items.size) return
                removeWithUndo(Store.current(), adapter.items[pos])
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                updateTabs()
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(recycler)
    }

    // ------------------------------------------------------------------ linked playlists

    private fun onLinkMore(anchor: View, link: PlaylistLink) {
        val list = Store.current()
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Sync now")
        pm.menu.add(0, 2, 1, "Open in YouTube")
        pm.menu.add(0, 3, 2, "Unlink (keeps the videos)")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Actions.importPlaylist(list, link.id)
                2 -> openUrl(YtLinks.playlistUrl(link.id))
                3 -> {
                    list.links.remove(link)
                    Store.changed()
                }
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_autoplay)?.isChecked = Store.autoplayNext
        menu.findItem(R.id.action_bg)?.isChecked = Store.backgroundPlay
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val list = Store.current()
        when (item.itemId) {
            R.id.action_add -> addDialog(linkOnly = false)
            R.id.action_sync -> {
                if (Store.lists.all { it.links.isEmpty() }) {
                    App.toast("No linked playlists yet. Open the Playlists tab to link one.", true)
                } else {
                    App.toast("Syncing…")
                    Actions.syncAll(force = true) { n -> App.toast(if (n == 0) "Up to date" else "$n new videos") }
                }
            }
            R.id.action_link -> addDialog(linkOnly = true)
            R.id.action_autoplay -> {
                Store.autoplayNext = !Store.autoplayNext
                Store.save()
                invalidateOptionsMenu()
            }
            R.id.action_bg -> {
                Store.backgroundPlay = !Store.backgroundPlay
                web.keepAlive = Store.backgroundPlay
                Store.save()
                if (!Store.backgroundPlay) PlaybackService.stop(this)
                else if (isPlaying) PlaybackService.start(this)
                invalidateOptionsMenu()
            }
            R.id.action_new_list -> newListDialog()
            R.id.action_rename -> renameDialog(list)
            R.id.action_clear -> Store.clearWatched(list)
            R.id.action_delete -> deleteDialog(list)
            R.id.action_wl -> MaterialAlertDialogBuilder(this)
                .setTitle("About Watch Later")
                .setMessage(YtLinks.WATCH_LATER_HELP)
                .setPositiveButton("OK", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ adding links

    private fun clipboardLink(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return null
        return if (YtLinks.parse(text) != null) text else null
    }

    private fun addDialog(linkOnly: Boolean) {
        val list = Store.current()
        val input = EditText(this).apply {
            hint = if (linkOnly) "Paste a playlist link" else "Paste a video or playlist link"
            setSingleLine()
            setText(clipboardLink() ?: "")
        }
        val box = FrameLayout(this).apply {
            val p = dp(20)
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (linkOnly) "Link a playlist to ${list.name}" else "Add to ${list.name}")
            .setView(box)
            .setPositiveButton(if (linkOnly) "Link" else "Add to queue") { _, _ -> handleLink(input.text.toString(), false, linkOnly) }
            .setNegativeButton("Cancel", null)
        if (!linkOnly) b.setNeutralButton("Play now") { _, _ -> handleLink(input.text.toString(), true, false) }
        b.show()
    }

    private fun handleLink(text: String, playNow: Boolean, linkOnly: Boolean) {
        val link = YtLinks.parse(text)
        if (link == null) {
            App.toast("That doesn't look like a YouTube link")
            return
        }
        val list = Store.current()
        val listId = link.listId
        val videoId = link.videoId
        when {
            listId != null && YtLinks.isPrivateList(listId) && (videoId == null || linkOnly) ->
                MaterialAlertDialogBuilder(this).setTitle("Watch Later is private")
                    .setMessage(YtLinks.WATCH_LATER_HELP).setPositiveButton("OK", null).show()
            listId != null && (videoId == null || linkOnly) -> Actions.importPlaylist(list, listId)
            linkOnly -> App.toast("That's a single video, not a playlist link")
            videoId != null && listId != null && !YtLinks.isPrivateList(listId) ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("This video is part of a playlist")
                    .setItems(arrayOf("Just this video", "Link the whole playlist")) { _, which ->
                        if (which == 0) addVideo(list, videoId, playNow) else Actions.importPlaylist(list, listId)
                    }
                    .show()
            videoId != null -> addVideo(list, videoId, playNow)
        }
    }

    private fun addVideo(list: VList, id: String, playNow: Boolean) {
        val v = Actions.addVideo(list, id, if (playNow) Pos.TOP else Pos.END)
        if (playNow) playVideo(list, v, true) else App.toast("Added to ${list.name}")
        if (playingId == null && !playNow) loadTop(false)
    }

    // ------------------------------------------------------------------ helpers

    private fun prompt(title: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(initial)
            setSelection(initial.length)
        }
        val box = FrameLayout(this).apply {
            val p = dp(20)
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVideo(id: String) {
        val v = Store.lists.firstNotNullOfOrNull { it.find(id) }
        val t = if (v != null && v.position > 5) "&t=${v.position}s" else ""
        openUrl(YtLinks.watchUrl(id) + t)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
