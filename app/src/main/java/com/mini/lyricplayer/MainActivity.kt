package com.mini.lyricplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private lateinit var npTitle: TextView
    private lateinit var npSub: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnOverlay: Button
    private lateinit var seekBar: android.widget.SeekBar
    private var tracks: List<Track> = emptyList()
    private var lastShownPath: String? = null
    private var loadGen = 0
    private var userSeeking = false

    /** Android 13+ 用细化的媒体权限，旧版本用存储权限 */
    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val listener = PlayerManager.Listener { state -> renderNowPlaying(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        npTitle = findViewById(R.id.npTitle)
        npSub = findViewById(R.id.npSub)
        btnPlay = findViewById(R.id.btnPlay)
        btnMode = findViewById(R.id.btnMode)
        btnOverlay = findViewById(R.id.btnOverlay)

        list.emptyView = emptyView
        list.adapter = TrackAdapter()
        list.setOnItemClickListener { _, _, position, _ -> PlayerManager.playQueue(tracks, position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDeleteTrack(tracks[position])
            true
        }
        btnPlay.setOnClickListener { PlayerManager.toggle() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { PlayerManager.next() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { PlayerManager.prev() }
        btnMode.setOnClickListener {
            PlayerManager.cyclePlayMode()
            renderMode()
            Toast.makeText(this, PlayerManager.playMode.label, Toast.LENGTH_SHORT).show()
        }
        btnOverlay.setOnClickListener { toggleOverlay() }
        seekBar = findViewById(R.id.seekBar)
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                userSeeking = false
                PlayerManager.seekTo((sb?.progress ?: 0).toLong())
            }
        })
        findViewById<ImageButton>(R.id.btnFolder).setOnClickListener {
            // CLEAR_TOP：销毁栈中旧实例，每次都从存储列表全新打开
            startActivity(
                Intent(this, FolderActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }

        if (hasAudioPermission()) loadTracks() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        PlayerManager.addListener(listener)
        // 每次回到前台都重扫，保证新增/变更的歌曲及时出现
        if (hasAudioPermission()) loadTracks()
        renderOverlayButton()
        renderMode()
    }

    override fun onPause() {
        super.onPause()
        PlayerManager.removeListener(listener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
        } else {
            emptyView.text = "未授予音乐读取权限，无法扫描本地音乐"
            Toast.makeText(this, "需要音乐读取权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT < 33) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    /** 后台线程加载列表（媒体库查询 + 文件系统扫描 + 标签补全都不阻塞 UI） */
    private fun loadTracks() {
        if (!hasAudioPermission()) return
        emptyView.text = getString(R.string.scanning)
        val gen = ++loadGen
        Thread {
            val loaded = MusicRepository.load(this)
            runOnUiThread {
                if (gen != loadGen || isDestroyed) return@runOnUiThread
                tracks = loaded
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                scrollToCurrent()
                if (tracks.isEmpty()) emptyView.text = "媒体库没有歌曲，点右上角文件夹图标直接浏览"
            }
            // 文件直扫的曲目缺歌手/时长，读标签补全后再刷新一次
            if (loaded.any { it.id == -1L }) {
                val enriched = MusicRepository.enrich(loaded)
                runOnUiThread {
                    if (gen != loadGen || isDestroyed || tracks.size != loaded.size) return@runOnUiThread
                    tracks = enriched
                    (list.adapter as BaseAdapter).notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "请允许「显示在其他应用的上层」后，再点一次开关", Toast.LENGTH_LONG).show()
            return
        }
        PlayerManager.saveOverlayEnabled(!PlayerManager.overlayEnabled)
        renderOverlayButton()
        PlayerManager.refresh()
    }

    private fun renderOverlayButton() {
        btnOverlay.text = getString(
            if (PlayerManager.overlayEnabled) R.string.overlay_on else R.string.overlay_off
        )
    }

    private fun renderMode() {
        btnMode.setImageResource(
            when (PlayerManager.playMode) {
                PlayerManager.PlayMode.SEQUENCE -> R.drawable.ic_sequence
                PlayerManager.PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one
                PlayerManager.PlayMode.REPEAT_ALL -> R.drawable.ic_repeat
                PlayerManager.PlayMode.SHUFFLE -> R.drawable.ic_shuffle
            }
        )
    }

    private fun renderNowPlaying(state: PlayerManager.State) {
        npTitle.text = state.track?.title ?: getString(R.string.not_playing)
        npSub.text = state.track?.artist ?: getString(R.string.tap_to_play)
        btnPlay.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // 进度条（用户拖动时不打断）
        val dur = (state.track?.durationMs ?: 0).toInt()
        if (seekBar.max != dur) seekBar.max = dur
        if (!userSeeking) seekBar.progress = state.positionMs.toInt().coerceAtMost(dur)
        // 切歌时：列表滚动定位到当前曲目并高亮（进度 tick 不会触发，避免打断用户浏览）
        val path = state.track?.path
        if (path != lastShownPath) {
            lastShownPath = path
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            scrollToCurrent()
        }
    }

    /** 滚动定位到正在播放的曲目 */
    private fun scrollToCurrent() {
        val path = PlayerManager.state.track?.path ?: return
        val pos = tracks.indexOfFirst { it.path == path }
        if (pos >= 0) list.setSelection(pos)
    }

    /** 长按列表项：确认后删除文件与媒体库记录 */
    private fun confirmDeleteTrack(track: Track) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除「${track.title}」吗？\n文件将从设备中移除，此操作不可恢复。")
            .setPositiveButton("删除") { _, _ -> deleteTrack(track) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteTrack(track: Track) {
        val fileOk = runCatching { File(track.path).delete() }.getOrDefault(false)
        var storeOk = false
        if (track.id >= 0) {
            storeOk = runCatching {
                contentResolver.delete(
                    Uri.withAppendedPath(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        track.id.toString()
                    ),
                    null, null,
                )
            }.getOrDefault(0) > 0
        }
        // 删除的是正在播放的曲目时停止播放
        if (PlayerManager.state.track?.path == track.path) PlayerManager.stop()
        loadTracks()
        if (fileOk || storeOk) {
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "删除失败（可能缺少写入权限）", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = tracks.size
        override fun getItem(position: Int) = tracks[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val t = tracks[position]
            view.findViewById<View>(R.id.rowContent).setBackgroundColor(
                if (position % 2 == 1) ContextCompat.getColor(this@MainActivity, R.color.surface) else 0
            )
            // 正在播放的曲目高亮：图标与标题用主题色加粗
            val isCurrent = t.path == PlayerManager.state.track?.path
            val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
            view.findViewById<ImageView>(R.id.rowIcon).apply {
                setImageResource(R.drawable.ic_music_note)
                imageTintList = ColorStateList.valueOf(
                    if (isCurrent) accent else 0xFF9AA0A6.toInt()
                )
            }
            view.findViewById<TextView>(R.id.title).apply {
                text = t.title
                setTextColor(if (isCurrent) accent else ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
                typeface = if (isCurrent) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            }
            view.findViewById<TextView>(R.id.subtitle).text =
                if (t.durationMs > 0) "${t.artist} · ${formatDuration(t.durationMs)}" else t.artist
            return view
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
