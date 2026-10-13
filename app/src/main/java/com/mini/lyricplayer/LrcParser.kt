package com.mini.lyricplayer

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {

    // 支持 [mm:ss] [mm:ss.xx] [mm:ss.xxx]，以及一行多个时间标签
    private val tagRegex = Regex("\\[(\\d{1,2}):(\\d{1,2}(?:[.:]\\d{1,3})?)]")

    /** 查找与歌曲同目录、同文件名（忽略大小写）的 .lrc 文件并解析；找不到返回空列表 */
    fun loadFor(songPath: String): List<LyricLine> {
        val song = File(songPath)
        val dir = song.parentFile ?: return emptyList()
        val base = song.nameWithoutExtension
        val lrcFile = dir.listFiles()?.firstOrNull {
            it.isFile &&
                it.extension.equals("lrc", ignoreCase = true) &&
                it.nameWithoutExtension.equals(base, ignoreCase = true)
        } ?: return emptyList()
        return runCatching { parse(decode(lrcFile.readBytes())) }.getOrDefault(emptyList())
    }

    /**
     * 智能解码：国内歌词文件常见 GBK/GB2312 编码，而 readText() 默认 UTF-8 会产生乱码。
     * 策略：先看 BOM（UTF-8/UTF-16），无 BOM 时做严格 UTF-8 校验，失败则按 GB18030 解码。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val strictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }
        return strictUtf8.getOrElse { String(bytes, charset("GB18030")) }
    }

    fun parse(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = tagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].replace(':', '.').toDouble()
                lines += LyricLine(minutes * 60_000 + (seconds * 1000).toLong(), text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 二分查找当前播放进度对应的歌词行（最后一个 timeMs <= positionMs 的行），没有则返回 -1 */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }
}
