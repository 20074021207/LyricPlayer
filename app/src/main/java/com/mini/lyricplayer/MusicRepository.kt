package com.mini.lyricplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import java.io.File

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val path: String,
)

object MusicRepository {

    private val AUDIO_EXT = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")

    /** 扫描标准音乐目录（含 U 盘等所有存储卷），供文件系统直扫补充用 */
    private val MUSIC_DIRS = listOf("Music", "music", "song", "Songs", "Download", "下载")

    /**
     * 加载本地音乐：媒体库 + 文件系统直扫合并。
     * 部分设备（电视盒子/运营商定制机）系统媒体扫描损坏，新文件永远进不了
     * MediaStore——只查媒体库会导致列表永远不更新。这里同时用 File API 扫描
     * 各存储卷的标准音乐目录，按路径去重后合并，保证刷新即可见新歌。
     */
    fun load(context: Context): List<Track> {
        val result = mutableListOf<Track>()
        val known = HashSet<String>()

        // 1) 媒体库（有标题/歌手/时长等元数据）
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                ),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000",
                null,
                null,
            )
        }.getOrNull()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(4) ?: continue
                known += normalize(path)
                result += Track(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'),
                    artist = c.getString(2)
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: "未知艺术家",
                    durationMs = c.getLong(3),
                    path = path,
                )
            }
        }

        // 2) 文件系统直扫补充（媒体扫描损坏的设备也能看到新歌）
        val volumes = runCatching {
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "self" }
        }.getOrNull() ?: emptyList()
        for (volume in volumes) {
            val root = if (volume.name == "emulated") File(volume, "0") else volume
            for (name in MUSIC_DIRS) {
                walk(File(root, name), result, known, depth = 0)
            }
        }

        return result.sortedBy { it.title.lowercase() }
    }

    /** 路径归一化（/sdcard 与 /storage/emulated/0 等价），用于去重 */
    private fun normalize(path: String): String =
        path.replace("/sdcard/", "/storage/emulated/0/").lowercase()

    /** 读取音频文件自带的标签（标题/歌手/时长），失败返回 null */
    fun readTags(path: String): Triple<String?, String?, Long>? = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (title == null && artist == null && duration <= 0) null else Triple(title, artist, duration)
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrNull()

    /**
     * 补全文件直扫曲目（id=-1）的标签信息（歌手/时长/规范标题）。
     * 读取标签涉及逐文件 IO，应在后台线程调用。
     */
    fun enrich(list: List<Track>): List<Track> {
        return list.map { t ->
            if (t.id != -1L) {
                t
            } else {
                val meta = readTags(t.path) ?: return@map t
                t.copy(
                    title = meta.first ?: t.title,
                    artist = meta.second ?: "未知艺术家",
                    durationMs = meta.third,
                )
            }
        }
    }

    /** 递归扫描音频文件（限制深度与总量，跳过隐藏目录与 Android 数据目录） */
    private fun walk(dir: File, out: MutableList<Track>, known: MutableSet<String>, depth: Int) {
        if (depth > 3 || out.size > 1000) return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.name == "Android") continue
                walk(f, out, known, depth + 1)
            } else if (f.extension.lowercase() in AUDIO_EXT) {
                val key = normalize(f.absolutePath)
                if (key in known) continue
                known += key
                out += Track(
                    id = -1,
                    title = f.nameWithoutExtension,
                    artist = dir.name,
                    durationMs = 0,
                    path = f.absolutePath,
                )
            }
        }
    }
}
