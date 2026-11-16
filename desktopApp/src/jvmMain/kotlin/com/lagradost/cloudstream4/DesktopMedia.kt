package com.lagradost.cloudstream4

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.prefs.Preferences

internal data class DesktopMedia(val title: String, val uri: URI, val description: String = "")

internal fun directMedia(input: String): DesktopMedia? {
    val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null || uri.userInfo != null) {
        return null
    }
    val pathTitle = uri.path.orEmpty().substringAfterLast('/').takeIf { it.isNotBlank() }
    return DesktopMedia(pathTitle ?: uri.host, uri)
}

internal class PlaybackProgress {
    private val preferences = runCatching {
        Preferences.userRoot().node("recloudstream/desktop-playback")
    }.getOrNull()

    fun read(uri: URI): Long = runCatching {
        preferences?.getLong(key(uri), 0L) ?: 0L
    }.getOrDefault(0L)

    fun write(uri: URI, millis: Long) {
        runCatching { preferences?.putLong(key(uri), millis) }
    }

    private fun key(uri: URI): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uri.toASCIIString().toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

internal data class SubtitleCue(val start: Long, val end: Long, val text: String)

private val subtitleTiming = Regex(
    """(\d{2,}:\d{2}:\d{2}[,.]\d{3})\s+-->\s+(\d{2,}:\d{2}:\d{2}[,.]\d{3})(?:\s.*)?"""
)

internal fun parseSrt(contents: String): List<SubtitleCue> =
    contents.trimStart('\uFEFF').lines().joinToString("\n").trim()
        .split(Regex("""\n[ \t]*\n+"""))
        .mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = if (lines.firstOrNull()?.trim()?.toIntOrNull() != null) 1 else 0
            val match = subtitleTiming.matchEntire(lines.getOrNull(timingIndex)?.trim().orEmpty())
                ?: return@mapNotNull null
            val start = timestampMillis(match.groupValues[1]) ?: return@mapNotNull null
            val end = timestampMillis(match.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (end > start && text.isNotEmpty()) SubtitleCue(start, end, text) else null
        }.sortedBy { it.start }

private fun timestampMillis(value: String): Long? {
    val parts = value.split(':', ',', '.').map { it.toLongOrNull() ?: return null }
    val (hours, minutes, seconds, milliseconds) = parts
    if (hours > 9999 || minutes > 59 || seconds > 59) return null
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + milliseconds
}
