package com.mini.lyricplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File

/**
 * 进程内唯一的播放器：持有 MediaPlayer 和播放队列，
 * 状态变化时通知所有监听者（主界面、前台服务、悬浮歌词）。
 * 集成音频焦点（来电/其他应用抢声音时暂停）、拔耳机暂停、
 * 播放状态持久化（重启应用可从上次位置继续）。
 */
object PlayerManager {

    /** 播放模式：顺序播放（播完停止）/ 单曲循环 / 列表循环 / 随机播放 */
    enum class PlayMode(val label: String) {
        SEQUENCE("顺序播放"),
        REPEAT_ONE("单曲循环"),
        REPEAT_ALL("列表循环"),
        SHUFFLE("随机播放"),
    }

    data class State(
        val track: Track? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val lyricLines: List<LyricLine> = emptyList(),
        val lyricIndex: Int = -1,
    )

    fun interface Listener {
        fun onPlayerStateChanged(state: State)
    }

    private const val PREFS = "prefs"

    var playMode = PlayMode.SEQUENCE
    var overlayEnabled = true

    private val listeners = mutableListOf<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var lastSyncTrack: Track? = null
    private var lastSyncPlaying: Boolean? = null
    private var queue: List<Track> = emptyList()
    private var queueIndex = -1

    /** 上次会话的恢复位置（点播放时从该位置继续） */
    private var pendingResumePos = 0L
    private var lastSavedPos = 0L

    // 音频焦点：Any 避免低版本类校验问题（API 26 以下不访问该字段）
    private var focusRequest: Any? = null
    private var pausedByFocusLoss = false

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state.playing) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume()
                }
            }
        }
    }

    private var noisyReceiver: BroadcastReceiver? = null

    var state = State()
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        // 恢复持久化设置
        playMode = prefs()?.getString("playMode", null)
            ?.let { n -> PlayMode.entries.firstOrNull { it.name == n } }
            ?: PlayMode.SEQUENCE
        overlayEnabled = prefs()?.getBoolean("overlayEnabled", true) ?: true
        // 拔出耳机/断开蓝牙音频时自动暂停
        if (noisyReceiver == null) {
            noisyReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (state.playing) pause()
                }
            }
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisyReceiver, filter)
            }
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPlayerStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        queueIndex = startIndex - 1
        next()
    }

    fun toggle() {
        if (state.playing) pause() else resume()
    }

    fun pause() {
        val p = player ?: return
        if (!p.isPlaying) return
        p.pause()
        stopTicking()
        update { copy(playing = false) }
        saveSession()
    }

    fun resume() {
        val context = appContext ?: return
        val t = state.track
        if (player == null && t != null) {
            // 恢复上次会话：从保存的位置继续
            val pos = pendingResumePos
            pendingResumePos = 0
            open(t, autoPlay = true, resumePosition = pos)
            return
        }
        val p = player ?: return
        if (p.isPlaying) return
        requestAudioFocus()
        p.start()
        startTicking()
        MusicService.start(context)
        update { copy(playing = true) }
    }

    /** 进度跳转（系统播控进度条 / 应用内进度条），歌词行同步更新 */
    fun seekTo(posMs: Long) {
        val p = player ?: return
        val pos = posMs.coerceIn(0, p.duration.toLong())
        p.seekTo(pos.toInt())
        lastSavedPos = pos
        lastSyncPlaying = null // 强制刷新 MediaSession 播放位置
        update {
            copy(
                positionMs = pos,
                lyricIndex = LrcParser.currentIndex(lyricLines, pos),
            )
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        when {
            // 随机播放：等概率跳到任意其他曲目
            playMode == PlayMode.SHUFFLE && queue.size > 1 -> {
                var i = queueIndex
                while (i == queueIndex) i = kotlin.random.Random.nextInt(queue.size)
                queueIndex = i
                open(queue[i])
            }
            queueIndex + 1 in queue.indices -> open(queue[++queueIndex])
            playMode == PlayMode.REPEAT_ALL -> open(queue[0])
            else -> stop()
        }
    }

    /** 上一首：播放超过 3 秒时回到本曲开头，否则切到队列中的上一首 */
    fun prev() {
        val p = player ?: return
        if (p.currentPosition > 3000) {
            p.seekTo(0)
            update {
                copy(positionMs = 0, lyricIndex = LrcParser.currentIndex(lyricLines, 0))
            }
            return
        }
        if (queueIndex - 1 >= 0) open(queue[--queueIndex])
    }

    fun stop() {
        stopTicking()
        player?.release()
        player = null
        queueIndex = -1
        abandonAudioFocus()
        saveSession() // 队列已耗尽，清空保存的会话
        update { State() }
        appContext?.let { MusicService.stop(it) }
    }

    fun cyclePlayMode() {
        playMode = PlayMode.entries[(playMode.ordinal + 1) % PlayMode.entries.size]
        prefs()?.edit()?.putString("playMode", playMode.name)?.apply()
        refresh()
    }

    fun saveOverlayEnabled(value: Boolean) {
        overlayEnabled = value
        prefs()?.edit()?.putBoolean("overlayEnabled", value)?.apply()
    }

    /** 手动把当前状态推给所有监听者（如切换桌面歌词开关后立即刷新悬浮窗） */
    fun refresh() = dispatch(state)

    /**
     * 恢复上次会话：重建播放队列并记住进度（不自动开播，点播放时继续）。
     * 在 App 启动时调用；队列文件不存在的条目自动剔除。
     */
    fun restoreLastSession() {
        val p = prefs() ?: return
        val arr = runCatching { JSONArray(p.getString("queue", null) ?: return) }.getOrNull() ?: return
        if (arr.length() == 0) return
        val files = (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf { it.isNotBlank() }?.let(::File) }
            .filter { it.exists() }
        if (files.isEmpty()) return
        queue = files.map {
            Track(id = -1, title = it.nameWithoutExtension, artist = it.parentFile?.name ?: "未知艺术家", durationMs = 0, path = it.absolutePath)
        }
        queueIndex = p.getInt("queueIndex", 0).coerceIn(0, queue.size - 1)
        pendingResumePos = p.getLong("resumePos", 0)
        val t = queue[queueIndex]
        val lines = LrcParser.loadFor(t.path)
        state = State(
            track = t,
            positionMs = pendingResumePos,
            lyricLines = lines,
            lyricIndex = LrcParser.currentIndex(lines, pendingResumePos),
        )
        // 立即激活 MediaSession（暂停态），系统播控/媒体键可直接恢复播放
        syncSession()
    }

    private fun open(track: Track, autoPlay: Boolean = true, resumePosition: Long = 0) {
        val context = appContext ?: return
        player?.release()
        val mp = MediaPlayer()
        player = mp
        val lines = LrcParser.loadFor(track.path)
        try {
            mp.setAudioAttributes(audioAttrs)
            // 文件夹浏览来的曲目（id=-1）只有路径，直接按路径播放；
            // 媒体库曲目优先按路径播放，失败再回退到 MediaStore URI
            try {
                mp.setDataSource(track.path)
            } catch (e: Exception) {
                if (track.id >= 0) {
                    mp.setDataSource(
                        context,
                        Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            track.id.toString()
                        )
                    )
                } else {
                    throw e
                }
            }
            mp.setOnPreparedListener { p ->
                if (resumePosition > 0) p.seekTo(resumePosition.toInt())
                if (autoPlay) {
                    requestAudioFocus()
                    p.start()
                    startTicking()
                }
                // 补齐时长并读取文件标签，正在播放栏与系统播控显示更准确
                val dur = p.duration.toLong()
                val startPos = if (resumePosition > 0) resumePosition else 0
                update {
                    val t = track
                    val enriched =
                        if (t.id == -1L) {
                            MusicRepository.readTags(t.path)?.let { (title, artist, duration) ->
                                t.copy(
                                    title = title ?: t.title,
                                    artist = artist ?: t.artist,
                                    durationMs = duration.takeIf { it > 0 } ?: t.durationMs,
                                )
                            } ?: t
                        } else {
                            t
                        }
                    val withDur = if (enriched.durationMs <= 0) enriched.copy(durationMs = dur) else enriched
                    State(
                        track = withDur,
                        playing = autoPlay,
                        positionMs = startPos,
                        lyricLines = lines,
                        lyricIndex = LrcParser.currentIndex(lines, startPos),
                    )
                }
            }
            mp.setOnCompletionListener { onTrackCompleted() }
            mp.setOnErrorListener { _, _, _ -> next(); true }
            mp.prepareAsync()
            if (autoPlay) MusicService.start(context)
            update { State(track = track, lyricLines = lines) }
            saveSession(0)
        } catch (e: Exception) {
            // 同步打开失败（文件损坏/无权限等）：跳到下一首
            mp.release()
            player = null
            next()
        }
    }

    /** 一首播完：单曲循环则重播本曲，否则进入下一首（列表循环时回到队首） */
    private fun onTrackCompleted() {
        if (playMode == PlayMode.REPEAT_ONE) {
            player?.let { p ->
                p.seekTo(0)
                p.start()
            }
            startTicking()
            update {
                copy(
                    playing = true,
                    positionMs = 0,
                    lyricIndex = LrcParser.currentIndex(lyricLines, 0),
                )
            }
        } else {
            next()
        }
    }

    private fun update(transform: State.() -> State) {
        state = state.transform()
        android.util.Log.i(
            "LPState",
            "track=${state.track?.title} playing=${state.playing} pos=${state.positionMs} " +
                "lyric[${state.lyricIndex}]=${state.lyricLines.getOrNull(state.lyricIndex)?.text}"
        )
        dispatch(state)
        syncSession()
    }

    private fun dispatch(s: State) {
        listeners.toList().forEach { it.onPlayerStateChanged(s) }
    }

    /** 保存当前播放队列与进度（供下次启动恢复） */
    private fun saveSession(position: Long = state.positionMs) {
        val p = prefs() ?: return
        if (queue.isEmpty() || queueIndex !in queue.indices) {
            p.edit().remove("queue").remove("queueIndex").remove("resumePos").apply()
            return
        }
        p.edit()
            .putString("queue", JSONArray(queue.map { it.path }).toString())
            .putInt("queueIndex", queueIndex)
            .putLong("resumePos", position)
            .apply()
    }

    // ---------- 音频焦点 ----------

    private fun requestAudioFocus() {
        val context = appContext ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = (focusRequest as? android.media.AudioFocusRequest)
                ?: android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { focusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val context = appContext ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (focusRequest as? android.media.AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // ---------- MediaSession：系统播控 ----------

    private fun ensureSession(): MediaSession? {
        val context = appContext ?: return null
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "LyricPlayer").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() = resume()
                        override fun onPause() = pause()
                        override fun onSkipToNext() = next()
                        override fun onSkipToPrevious() = prev()
                        override fun onSeekTo(pos: Long) = seekTo(pos)
                        override fun onStop() = stop()
                    },
                    Handler(Looper.getMainLooper()),
                )
                isActive = false
            }
        }
        return mediaSession
    }

    /** 同步 MediaSession 状态（仅在曲目/播放状态变化时刷新，避免 200ms ticker 刷屏） */
    private fun syncSession() {
        val session = ensureSession() ?: return
        val st = state
        if (st.track == lastSyncTrack && st.playing == lastSyncPlaying) return
        lastSyncTrack = st.track
        lastSyncPlaying = st.playing
        session.isActive = st.track != null
        session.setMetadata(
            st.track?.let {
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, it.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, it.durationMs)
                    .build()
            }
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    st.positionMs,
                    if (st.playing) 1f else 0f,
                )
                .build()
        )
    }

    /** 每 200ms 轮询播放进度，驱动歌词行切换，并定期保存进度 */
    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            val pos = p.currentPosition.toLong()
            val index = LrcParser.currentIndex(state.lyricLines, pos)
            if (index != state.lyricIndex || pos != state.positionMs) {
                update { copy(positionMs = pos, lyricIndex = index) }
            }
            if (kotlin.math.abs(pos - lastSavedPos) >= 5000) {
                lastSavedPos = pos
                saveSession(pos)
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun startTicking() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 200)
    }

    private fun stopTicking() {
        handler.removeCallbacks(ticker)
    }
}
