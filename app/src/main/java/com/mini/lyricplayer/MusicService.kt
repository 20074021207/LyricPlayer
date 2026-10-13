package com.mini.lyricplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 前台服务：仅负责保活进程和展示播放通知，播放逻辑在 PlayerManager */
class MusicService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.mini.lyricplayer.action.TOGGLE"
        const val ACTION_CLOSE = "com.mini.lyricplayer.action.CLOSE"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MusicService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, MusicService::class.java))
    }

    private var lastTrack: Track? = null
    private var lastPlaying = false

    private val listener = PlayerManager.Listener { state ->
        if (state.track == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (state.track !== lastTrack || state.playing != lastPlaying) {
            // 进度 tick 每 200ms 触发一次状态回调，只在曲目/播放状态变化时才更新通知，避免通知刷屏被系统限流
            lastTrack = state.track
            lastPlaying = state.playing
            notifyNowPlaying()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlayerManager.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerManager.toggle()
            ACTION_CLOSE -> PlayerManager.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PlayerManager.removeListener(listener)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "播放通知", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyNowPlaying() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = PlayerManager.state
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, title: String) = NotificationCompat.Action(
            0, title,
            PendingIntent.getService(
                this, a.hashCode(), Intent(this, MusicService::class.java).setAction(a),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(state.track?.title ?: getString(R.string.not_playing))
            .setContentText(state.track?.artist ?: "")
            .setContentIntent(contentPi)
            .addAction(
                action(
                    ACTION_TOGGLE,
                    getString(if (state.playing) R.string.pause else R.string.play)
                )
            )
            .addAction(action(ACTION_CLOSE, "关闭"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
