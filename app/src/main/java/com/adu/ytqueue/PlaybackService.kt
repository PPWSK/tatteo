package com.adu.ytqueue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest

/** Keeps the app alive while playing, and shows play/pause/next on the lock screen and in notifications. */
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 7
        private const val A_PLAY = "play"
        private const val A_PAUSE = "pause"
        private const val A_NEXT = "next"
        private const val A_BACK = "back"
        private const val A_CLOSE = "close"

        var running = false
            private set

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    private lateinit var session: MediaSessionCompat
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var art: Bitmap? = null
    private var artId: String? = null
    private val busListener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )

        session = MediaSessionCompat(this, "ytqueue").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlayerBus.controller?.play() }
                override fun onPause() { PlayerBus.controller?.pause() }
                override fun onSkipToNext() { PlayerBus.controller?.next() }
                override fun onRewind() { PlayerBus.controller?.seekBy(-10) }
                override fun onFastForward() { PlayerBus.controller?.seekBy(30) }
                override fun onStop() { PlayerBus.controller?.close() }
            })
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService, 0,
                    Intent(this@PlaybackService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            isActive = true
        }

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytqueue:playback").apply { setReferenceCounted(false) }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "ytqueue:playback").apply { setReferenceCounted(false) }

        PlayerBus.addListener(busListener)
        goForeground()
        refresh()
    }

    private fun goForeground() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val c = PlayerBus.controller
        when (intent?.action) {
            A_PLAY -> c?.play()
            A_PAUSE -> c?.pause()
            A_NEXT -> c?.next()
            A_BACK -> c?.seekBy(-10)
            A_CLOSE -> {
                c?.pause()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (!running) return
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (PlayerBus.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlayerBus.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlayerBus.subtitle)
        art?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        if (PlayerBus.playing) {
            wake?.acquire(8 * 60 * 60 * 1000L)
            wifi?.acquire()
        } else {
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) wifi?.release()
        }
        loadArt()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
    }

    private fun loadArt() {
        val id = PlayerBus.videoId ?: return
        if (id == artId) return
        artId = id
        art = null
        val req = ImageRequest.Builder(this)
            .data(YtLinks.thumb(id))
            .allowHardware(false)
            .target { d ->
                if (artId == id) {
                    art = (d as? BitmapDrawable)?.bitmap
                    refresh()
                }
            }
            .build()
        imageLoader.enqueue(req)
    }

    private fun action(icon: Int, title: String, act: String) = NotificationCompat.Action(
        icon, title,
        PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun build(): Notification {
        val playPause = if (PlayerBus.playing) action(R.drawable.ic_pause, "Pause", A_PAUSE)
        else action(R.drawable.ic_play, "Play", A_PLAY)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(PlayerBus.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(PlayerBus.subtitle)
            .setLargeIcon(art)
            .setContentIntent(session.controller.sessionActivity)
            .setOngoing(PlayerBus.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .addAction(action(R.drawable.ic_replay, "Back 10s", A_BACK))
            .addAction(playPause)
            .addAction(action(R.drawable.ic_next, "Next", A_NEXT))
            .addAction(action(R.drawable.ic_close, "Close", A_CLOSE))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onDestroy() {
        running = false
        PlayerBus.removeListener(busListener)
        if (wake?.isHeld == true) wake?.release()
        if (wifi?.isHeld == true) wifi?.release()
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
