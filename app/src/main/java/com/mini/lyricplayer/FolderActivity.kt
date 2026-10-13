package com.mini.lyricplayer

import android.content.res.ColorStateList
import android.os.Bundle
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
import java.io.File

/**
 * 文件夹浏览选歌：不依赖系统 MediaStore，直接遍历文件系统。
 * 用于媒体扫描损坏/不刷新的设备（部分电视盒子、运营商定制机）。
 * 返回键 = 返回上级目录；点击音频 = 从该首开始播放整个文件夹；
 * 长按文件夹 = 播放整个文件夹；长按音频文件 = 删除。
 */
class FolderActivity : AppCompatActivity() {

    companion object {
        private val AUDIO_EXT = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
    }

    private class Row(
        val file: File,
        val isDir: Boolean,
        val sizeBytes: Long,
        val isVolume: Boolean = false,
    )

    private lateinit var emptyView: TextView
    private lateinit var pathView: TextView
    private lateinit var btnPlayAll: Button
    private var dir: File = File("/storage")
    private var startDir: File = dir
    private var rows: List<Row> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val row = rows[position]
            view.findViewById<View>(R.id.rowContent).setBackgroundColor(
                if (position % 2 == 1) androidx.core.content.ContextCompat.getColor(
                    this@FolderActivity, R.color.surface
                ) else 0
            )
            val (iconRes, tint) = when {
                row.isVolume && row.file.name == "0" -> R.drawable.ic_phone to 0xFF4C7DFF.toInt()
                row.isVolume -> R.drawable.ic_usb to 0xFF4C7DFF.toInt()
                row.isDir -> R.drawable.ic_folder to 0xFF4C7DFF.toInt()
                else -> R.drawable.ic_music_note to 0xFF9AA0A6.toInt()
            }
            view.findViewById<ImageView>(R.id.rowIcon).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(tint)
            }
            view.findViewById<TextView>(R.id.title).text = when {
                !row.isDir -> row.file.name
                row.isVolume && row.file.name == "0" -> "本机存储"
                row.isVolume -> "${row.file.name}（U盘/存储卡）"
                else -> row.file.name
            }
            view.findViewById<TextView>(R.id.subtitle).text = when {
                row.isVolume -> "存储设备"
                row.isDir -> "文件夹（长按播放全部）"
                else -> "${row.sizeBytes / 1024} KB（长按删除）"
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder)
        val list = findViewById<ListView>(R.id.folderList)
        pathView = findViewById(R.id.folderPath)
        emptyView = findViewById(R.id.folderEmpty)
        btnPlayAll = findViewById(R.id.btnPlayAll)
        list.emptyView = emptyView
        list.adapter = adapter

        // 从存储列表开始：可进入本机存储与已插入的 U 盘/存储卡；不可用时退回本机存储
        dir = File("/storage")
        if (dir.listFiles() == null) dir = android.os.Environment.getExternalStorageDirectory()
        startDir = dir

        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            if (row.isDir) {
                dir = row.file
                load()
            } else {
                playFolder(dir, row.file)
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows[position]
            if (row.isDir) playFolder(row.file)
            else confirmDeleteFile(row.file)
            true
        }
        btnPlayAll.setOnClickListener { playFolder(dir) }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            load()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回上级目录；已在起始存储列表时直接退出。/storage/emulated/0 直接跳回 /storage
        if (dir != startDir) {
            val parent = if (dir.absolutePath == "/storage/emulated/0") File("/storage") else dir.parentFile
            if (parent != null) {
                dir = parent
                load()
                return
            }
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 每次显示时重载，外部新增/删除的文件能及时反映
        load()
    }

    private fun load() {
        pathView.text = dir.absolutePath
        val files = dir.listFiles()
        if (files == null) {
            rows = emptyList()
            emptyView.text = getString(R.string.folder_unreadable)
        } else {
            rows = files.filter { !it.name.startsWith(".") }
                .filter { !(it.parentFile?.absolutePath == "/storage" && it.name == "self") } // 系统自引用目录
                .filter { it.isDirectory || it.extension.lowercase() in AUDIO_EXT }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map { f ->
                    val isVolume = f.parentFile?.absolutePath == "/storage"
                    // 本机存储直达用户存储根 /storage/emulated/0，跳过中间层
                    val target =
                        if (isVolume && f.name == "emulated") File(f, "0").takeIf { it.isDirectory } ?: f
                        else f
                    Row(target, target.isDirectory, if (target.isDirectory) 0 else target.length(), isVolume)
                }
            if (rows.isEmpty()) emptyView.text = getString(R.string.folder_empty)
        }
        val audioCount = rows.count { !it.isDir }
        btnPlayAll.visibility = if (audioCount > 0) View.VISIBLE else View.GONE
        btnPlayAll.text = getString(R.string.play_all) + "（$audioCount 首）"
        adapter.notifyDataSetChanged()
    }

    /** 播放整个文件夹（按文件名排序），可从 startFile 指定的那首开始 */
    private fun playFolder(folder: File, startFile: File? = null) {
        val audio = folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        if (audio.isEmpty()) {
            Toast.makeText(this, R.string.folder_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = audio.map {
            Track(id = -1, title = it.nameWithoutExtension, artist = folder.name, durationMs = 0, path = it.absolutePath)
        }
        val index = startFile?.let { audio.indexOf(it) }?.coerceAtLeast(0) ?: 0
        PlayerManager.playQueue(tracks, index)
        finish()
    }

    /** 长按音频文件：确认后删除 */
    private fun confirmDeleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除「${file.name}」吗？\n此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                val ok = runCatching { file.delete() }.getOrDefault(false)
                if (ok && PlayerManager.state.track?.path == file.absolutePath) PlayerManager.stop()
                Toast.makeText(
                    this,
                    if (ok) "已删除" else "删除失败（可能缺少写入权限）",
                    Toast.LENGTH_SHORT,
                ).show()
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
