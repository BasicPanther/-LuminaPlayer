package com.example.data.service

import java.io.File
import java.util.regex.Pattern

/**
 * High-performance, zero-latency subtitle cue model and parser for .srt and .vtt subtitle formats.
 * Enables instant micro-second offset synchronization and custom styling in Jetpack Compose.
 */
data class SubtitleCue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleParser {

    // Regex supporting both SRT (hh:mm:ss,ms) and WebVTT (hh:mm:ss.ms or mm:ss.ms)
    // Examples:
    // 00:01:23,456 --> 00:01:26,789
    // 00:01:23.456 --> 00:01:26.789
    // 01:23.456 --> 01:26.789
    private val TIMESTAMP_PATTERN: Pattern = Pattern.compile(
        """(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})"""
    )

    private val HTML_TAG_REGEX: Regex = Regex("<[^>]*>")

    /**
     * Parses raw subtitle string content (SRT or WebVTT) into an ordered list of SubtitleCues.
     */
    fun parse(content: String): List<SubtitleCue> {
        if (content.isBlank()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.lines()

        var i = 0
        var cueIndex = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val matcher = TIMESTAMP_PATTERN.matcher(line)

            if (matcher.find()) {
                val startH = matcher.group(1)?.toLongOrNull() ?: 0L
                val startM = matcher.group(2).toLong()
                val startS = matcher.group(3).toLong()
                val startMs = matcher.group(4).toLong()
                val startTime = startH * 3600000L + startM * 60000L + startS * 1000L + startMs

                val endH = matcher.group(5)?.toLongOrNull() ?: 0L
                val endM = matcher.group(6).toLong()
                val endS = matcher.group(7).toLong()
                val endMs = matcher.group(8).toLong()
                val endTime = endH * 3600000L + endM * 60000L + endS * 1000L + endMs

                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val textLine = lines[i].trim()
                    // Filter out WebVTT header blocks or metadata
                    if (!textLine.startsWith("NOTE") && !textLine.startsWith("STYLE") && !textLine.startsWith("REGION")) {
                        val cleanText = textLine.replace(HTML_TAG_REGEX, "").trim()
                        if (cleanText.isNotEmpty()) {
                            textLines.add(cleanText)
                        }
                    }
                    i++
                }

                if (textLines.isNotEmpty() && endTime >= startTime) {
                    cues.add(
                        SubtitleCue(
                            id = ++cueIndex,
                            startMs = startTime,
                            endMs = endTime,
                            text = textLines.joinToString("\n")
                        )
                    )
                }
            } else {
                i++
            }
        }

        return cues.sortedBy { it.startMs }
    }

    /**
     * Reads and parses a subtitle file safely.
     */
    fun parseFile(file: File?): List<SubtitleCue> {
        if (file == null || !file.exists() || !file.canRead()) return emptyList()
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            try {
                // Fallback attempt with ISO-8859-1 for legacy foreign subtitles
                parse(file.readText(Charsets.ISO_8859_1))
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Resolves the active cue at a specific target playback time (in milliseconds).
     * Returns null if no cue is active at this exact moment.
     */
    fun getActiveCue(cues: List<SubtitleCue>, targetTimeMs: Long): SubtitleCue? {
        if (cues.isEmpty() || targetTimeMs < 0) return null

        // Binary search for efficiency across long media files (thousands of cues)
        var low = 0
        var high = cues.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]

            when {
                targetTimeMs < cue.startMs -> high = mid - 1
                targetTimeMs > cue.endMs -> low = mid + 1
                else -> return cue // Found matching cue!
            }
        }

        // Secondary linear boundary check for overlapping cue blocks
        val approxIndex = low.coerceIn(0, cues.size - 1)
        val searchStart = (approxIndex - 3).coerceAtLeast(0)
        val searchEnd = (approxIndex + 3).coerceAtMost(cues.size - 1)

        for (j in searchStart..searchEnd) {
            val cue = cues[j]
            if (targetTimeMs in cue.startMs..cue.endMs) {
                return cue
            }
        }

        return null
    }

    /**
     * Generates a standard SRT string from shifted cues for exporting or caching.
     */
    fun formatToSrt(cues: List<SubtitleCue>, offsetMs: Long = 0L): String {
        val sb = StringBuilder()
        cues.forEachIndexed { index, cue ->
            val shiftedStart = (cue.startMs + offsetMs).coerceAtLeast(0L)
            val shiftedEnd = (cue.endMs + offsetMs).coerceAtLeast(0L)

            sb.append(index + 1).append("\n")
            sb.append(formatTimestamp(shiftedStart))
                .append(" --> ")
                .append(formatTimestamp(shiftedEnd))
                .append("\n")
            sb.append(cue.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatTimestamp(ms: Long): String {
        val h = ms / 3600000L
        val m = (ms % 3600000L) / 60000L
        val s = (ms % 60000L) / 1000L
        val millis = ms % 1000L
        return String.format("%02d:%02d:%02d,%03d", h, m, s, millis)
    }
}
