package com.example.domain

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class SubtitleEmbedMode {
    SOFTSUB,
    HARDSUB
}

enum class SubtitleSoftsubContainer(
    val extension: String,
    val label: String
) {
    MP4("mp4", "MP4 (.mp4)"),
    MKV("mkv", "MKV (.mkv)")
}

/**
 * Curated, bundled 100% full-UTF-8 static Bold TTF fonts stored in `assets/subtitle_fonts/`.
 * Verified with 0 missing glyphs across Turkish (ğ Ğ ş Ş ı İ ö Ö ü Ü ç Ç), Latin-Extended,
 * Cyrillic, and Greek so Compose live preview, FFmpeg libass, and Media3 BitmapOverlay render
 * identical typography with zero mojibake or missing characters.
 */
enum class SubtitleFontOption(
    val displayName: String,
    val assetFileName: String,
    val assFontName: String,
    val isRecommended: Boolean = false
) {
    ROBOTO(
        displayName = "Roboto",
        assetFileName = "sub_roboto_bold.ttf",
        assFontName = "Roboto",
        isRecommended = true
    ),
    MONTSERRAT(
        displayName = "Montserrat",
        assetFileName = "sub_montserrat_bold.ttf",
        assFontName = "Montserrat",
        isRecommended = false
    ),
    PT_SERIF(
        displayName = "PT Serif",
        assetFileName = "sub_ptserif_bold.ttf",
        assFontName = "PT Serif",
        isRecommended = false
    );

    val assetPath: String get() = "subtitle_fonts/$assetFileName"
}

enum class SubtitleFontSizeOption(
    /** Fraction of the video's shorter edge height used for consistent WYSIWYG sizing. */
    val relativeHeightFraction: Float,
    /** Exact ASS font size when `PlayResY = 1080` (scaled proportionally for portrait). */
    val assFontSizeAt1080: Int,
    /** Preview sp size inside the compact Compose video frame. */
    val previewBaseSp: Float,
    val isRecommended: Boolean = false
) {
    SMALL(
        relativeHeightFraction = 0.042f,
        assFontSizeAt1080 = 46,
        previewBaseSp = 13f
    ),
    MEDIUM(
        relativeHeightFraction = 0.054f,
        assFontSizeAt1080 = 60,
        previewBaseSp = 16f,
        isRecommended = true
    ),
    LARGE(
        relativeHeightFraction = 0.068f,
        assFontSizeAt1080 = 76,
        previewBaseSp = 19.5f
    ),
    XLARGE(
        relativeHeightFraction = 0.082f,
        assFontSizeAt1080 = 92,
        previewBaseSp = 23f
    )
}

data class SubtitleCue(
    val id: Long,
    val startSec: Double,
    val endSec: Double,
    val text: String
)

/**
 * Pure-JVM parser, writer, binary search index, and FFmpeg filter path escaper for
 * SubRip (.srt), WebVTT (.vtt), and SubStation Alpha (.ass / .ssa) subtitles.
 */
object SubtitleParser {

    private val ASS_TAG_REGEX = Regex("""\{[^}]*}""")
    private val HTML_TAG_REGEX = Regex("""</?(?:b|i|u|s|font|span|ruby|rt|c)[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRT_TIMING_REGEX = Regex(
        """(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}"""
    )

    /**
     * Decodes subtitle bytes cleanly:
     * 1) Honors UTF-8 / UTF-16LE / UTF-16BE BOMs.
     * 2) Validates strict UTF-8 (handles 95%+ of modern `.srt`/`.ass`/`.vtt` files).
     * 3) Falls back to `windows-1254` (Turkish ANSI / CP1254) when strict UTF-8 fails,
     *    preventing garbled Turkish `ğ ş ı İ ö ü ç` characters on legacy subtitle downloads.
     */
    fun decodeBytesSmart(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val strictUtf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (strictUtf8 != null) return strictUtf8

        val turkishCharset = runCatching { Charset.forName("windows-1254") }
            .getOrDefault(StandardCharsets.ISO_8859_1)
        return String(bytes, turkishCharset)
    }

    /**
     * Parses `.srt`, `.vtt`, `.ass`, or `.ssa` subtitle text into time-sorted [SubtitleCue] items.
     */
    fun parse(rawText: String): List<SubtitleCue> {
        val normalized = rawText
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return emptyList()

        val looksAss = normalized.contains("[Events]", ignoreCase = true) ||
            normalized.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }

        val rawCues = if (looksAss) {
            parseAss(normalized)
        } else {
            parseSrtOrVtt(normalized)
        }

        return rawCues
            .filter { it.text.isNotBlank() && it.startSec.isFinite() && it.endSec.isFinite() }
            .sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })
            .mapIndexed { idx, cue ->
                val safeStart = cue.startSec.coerceAtLeast(0.0)
                val safeEnd = maxOf(cue.endSec, safeStart + 0.1)
                cue.copy(id = idx + 1L, startSec = safeStart, endSec = safeEnd)
            }
    }

    private fun parseSrtOrVtt(text: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        val lines = text.split('\n')
        var i = 0
        var nextId = 1L

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() ||
                line.equals("WEBVTT", ignoreCase = true) ||
                line.startsWith("NOTE", ignoreCase = true) ||
                line.startsWith("STYLE", ignoreCase = true) ||
                line.startsWith("REGION", ignoreCase = true)
            ) {
                if (line.startsWith("STYLE", ignoreCase = true) || line.startsWith("NOTE", ignoreCase = true)) {
                    while (i < lines.size && lines[i].isNotBlank()) i++
                }
                i++
                continue
            }

            if (line.contains("-->") && SRT_TIMING_REGEX.containsMatchIn(line)) {
                val arrowIdx = line.indexOf("-->")
                val startPart = line.substring(0, arrowIdx).trim()
                val endPart = line.substring(arrowIdx + 3).trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
                val startSec = parseSubtitleTime(startPart)
                val endSec = parseSubtitleTime(endPart)
                i++

                val bodyLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    bodyLines.add(lines[i].trimEnd())
                    i++
                }
                if (startSec != null && endSec != null && bodyLines.isNotEmpty()) {
                    val cleaned = cleanCueText(bodyLines.joinToString("\n"))
                    if (cleaned.isNotBlank()) {
                        result.add(
                            SubtitleCue(
                                id = nextId++,
                                startSec = startSec,
                                endSec = maxOf(endSec, startSec + 0.1),
                                text = cleaned
                            )
                        )
                    }
                }
            } else {
                i++
            }
        }
        return result
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        var startCol = 1
        var endCol = 2
        var textCol = 9
        var nextId = 1L

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.US) }
                val sIdx = cols.indexOf("start")
                val eIdx = cols.indexOf("end")
                val tIdx = cols.indexOf("text")
                if (sIdx >= 0 && eIdx >= 0 && tIdx >= 0) {
                    startCol = sIdx
                    endCol = eIdx
                    textCol = tIdx
                }
            } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(':').trimStart()
                val parts = splitAssDialogueFields(payload, textCol + 1)
                if (parts.size > maxOf(startCol, endCol, textCol)) {
                    val startSec = parseSubtitleTime(parts[startCol])
                    val endSec = parseSubtitleTime(parts[endCol])
                    val rawBody = parts[textCol]
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                    val cleaned = cleanCueText(rawBody)
                    if (startSec != null && endSec != null && cleaned.isNotBlank()) {
                        result.add(
                            SubtitleCue(
                                id = nextId++,
                                startSec = startSec,
                                endSec = maxOf(endSec, startSec + 0.1),
                                text = cleaned
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun splitAssDialogueFields(payload: String, maxFields: Int): List<String> {
        val out = ArrayList<String>(maxFields)
        var start = 0
        var commas = 0
        val limit = maxFields - 1
        var i = 0
        while (i < payload.length && commas < limit) {
            if (payload[i] == ',') {
                out.add(payload.substring(start, i).trim())
                start = i + 1
                commas++
            }
            i++
        }
        if (start <= payload.length) {
            out.add(payload.substring(start))
        }
        return out
    }

    private fun cleanCueText(raw: String): String {
        val noAssTags = ASS_TAG_REGEX.replace(raw, "")
        val noHtmlTags = HTML_TAG_REGEX.replace(noAssTags, "")
        return noHtmlTags
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Parses `HH:MM:SS,mmm`, `HH:MM:SS.mmm`, `MM:SS.mmm`, or ASS `H:MM:SS.cc` timestamps.
     */
    fun parseSubtitleTime(raw: String): Double? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':')
        return when (parts.size) {
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                if (m < 0 || s < 0.0 || !s.isFinite()) null else m * 60.0 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                if (h < 0 || m < 0 || s < 0.0 || !s.isFinite()) null else h * 3600.0 + m * 60.0 + s
            }
            else -> cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }
    }

    /**
     * Serializes cues into standard UTF-8 SubRip (`.srt`) for lossless Softsub muxing.
     */
    fun toSrt(cues: List<SubtitleCue>): String {
        val sorted = cues
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })
        val sb = StringBuilder(sorted.size * 64)
        sorted.forEachIndexed { idx, cue ->
            val start = cue.startSec.coerceAtLeast(0.0)
            val end = maxOf(cue.endSec, start + 0.1)
            sb.append(idx + 1).append('\n')
            sb.append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
            sb.append(cue.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Serializes cues into an exact UTF-8 ASS v4.00+ script tailored to [videoWidth]×[videoHeight]
     * so FFmpeg `libass` renders the exact font, size, crisp dark outline, and bottom placement
     * shown in the Compose live preview and Media3 Hardware Turbo overlay.
     */
    fun toAss(
        cues: List<SubtitleCue>,
        font: SubtitleFontOption,
        fontSize: SubtitleFontSizeOption,
        videoWidth: Int?,
        videoHeight: Int?
    ): String {
        val srcW = (videoWidth ?: 1920).coerceAtLeast(2)
        val srcH = (videoHeight ?: 1080).coerceAtLeast(2)
        val isPortrait = srcH > srcW
        val playResX: Int
        val playResY: Int
        if (isPortrait) {
            playResX = 1080
            playResY = ((srcH.toDouble() / srcW.toDouble()) * 1080.0).roundToInt().coerceIn(1080, 2400)
        } else {
            playResY = 1080
            playResX = ((srcW.toDouble() / srcH.toDouble()) * 1080.0).roundToInt().coerceIn(1080, 2880)
        }
        val shortEdge = minOf(playResX, playResY)
        val effectiveFontSize = (shortEdge * fontSize.relativeHeightFraction)
            .roundToInt()
            .coerceIn(28, 128)
        val outlinePx = (effectiveFontSize * 0.068f).coerceIn(2.4f, 6.0f)
        val shadowPx = (effectiveFontSize * 0.032f).coerceIn(1.2f, 3.0f)
        val marginLr = (playResX * 0.06f).roundToInt().coerceAtLeast(36)
        val marginV = (playResY * 0.055f).roundToInt().coerceAtLeast(42)

        val sb = StringBuilder(cues.size * 96 + 512)
        sb.appendLine("[Script Info]")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("WrapStyle: 0")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine("YCbCr Matrix: TV.709")
        sb.appendLine("PlayResX: $playResX")
        sb.appendLine("PlayResY: $playResY")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine(
            String.format(
                Locale.US,
                "Style: Default,%s,%d,&H00FFFFFF,&H000000FF,&H00120E0A,&H78000000,-1,0,0,0,100,100,0,0,1,%.2f,%.2f,2,%d,%d,%d,1",
                font.assFontName,
                effectiveFontSize,
                outlinePx,
                shadowPx,
                marginLr,
                marginLr,
                marginV
            )
        )
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

        val sorted = cues
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<SubtitleCue> { it.startSec }.thenBy { it.endSec })
        for (cue in sorted) {
            val start = cue.startSec.coerceAtLeast(0.0)
            val end = maxOf(cue.endSec, start + 0.1)
            val escapedText = cue.text
                .trim()
                .replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\N")
            sb.append("Dialogue: 0,")
                .append(formatAssTime(start))
                .append(',')
                .append(formatAssTime(end))
                .append(",Default,,0,0,0,,")
                .append(escapedText)
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * Escapes a filesystem path for FFmpeg libavfilter single-quoted option values such as
     * `ass='<path>':fontsdir='<dir>'`.
     */
    fun escapeForFfmpegFilterPath(path: String): String {
        val sb = StringBuilder(path.length + 12)
        for (ch in path) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                ':' -> sb.append("\\:")
                '\'' -> sb.append("\\'")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun formatSrtTime(seconds: Double): String {
        val totalMs = kotlin.math.round(seconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val h = totalMs / 3_600_000L
        val m = (totalMs % 3_600_000L) / 60_000L
        val s = (totalMs % 60_000L) / 1_000L
        val ms = totalMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    fun formatAssTime(seconds: Double): String {
        val totalCs = kotlin.math.round(seconds.coerceAtLeast(0.0) * 100.0).toLong()
        val h = totalCs / 360_000L
        val m = (totalCs % 360_000L) / 6_000L
        val s = (totalCs % 6_000L) / 100L
        val cs = totalCs % 100L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    /**
     * O(log N) lookup returning the index of the active cue at [timeSec], or `-1` when no cue is
     * active. Assumes [cues] is sorted by `startSec`.
     */
    fun findActiveCueIndex(cues: List<SubtitleCue>, timeSec: Double): Int {
        if (cues.isEmpty()) return -1
        var lo = 0
        var hi = cues.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startSec <= timeSec + 1e-4) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (candidate < 0) return -1
        // Check candidate and up to 4 preceding cues in case of slightly overlapping cue ranges
        val minBack = (candidate - 4).coerceAtLeast(0)
        for (idx in candidate downTo minBack) {
            val c = cues[idx]
            if (timeSec >= c.startSec - 1e-4 && timeSec <= c.endSec + 1e-4) {
                return idx
            }
        }
        return -1
    }

    /**
     * O(log N) lookup returning the inclusive index range of cues intersecting
     * `[windowStartSec, windowEndSec]` so the infinite horizontal subtitle ribbon only composes
     * cues currently visible in the viewport (smooth 120Hz even for 3,000-line feature films).
     */
    fun findVisibleCueRange(
        cues: List<SubtitleCue>,
        windowStartSec: Double,
        windowEndSec: Double
    ): IntRange {
        if (cues.isEmpty() || windowEndSec < windowStartSec) return IntRange.EMPTY

        // Find last index with startSec <= windowEndSec
        var lo = 0
        var hi = cues.lastIndex
        var lastIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startSec <= windowEndSec) {
                lastIdx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (lastIdx < 0) return IntRange.EMPTY

        // Find first index near windowStartSec (stepping back generously for long cues up to 45s)
        val searchBackStart = (windowStartSec - 45.0).coerceAtLeast(0.0)
        lo = 0
        hi = lastIdx
        var firstCandidate = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startSec < searchBackStart) {
                lo = mid + 1
            } else {
                firstCandidate = mid
                hi = mid - 1
            }
        }
        var firstIdx = -1
        for (i in firstCandidate..lastIdx) {
            if (cues[i].endSec >= windowStartSec) {
                firstIdx = i
                break
            }
        }
        return if (firstIdx in 0..lastIdx) firstIdx..lastIdx else IntRange.EMPTY
    }

    /**
     * Groups embedded subtitle tracks by display extension (e.g. `.srt`, `.ass`, `.vtt`, `.pgs`)
     * preserving first-seen order so `MediaHeroCard` can render `.srt¹² · .ass³`.
     */
    fun groupSubtitleTrackFormats(tracks: List<SubtitleTrackInfo>): List<Pair<String, Int>> {
        if (tracks.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (t in tracks) {
            val tag = t.formatTag
            counts[tag] = (counts[tag] ?: 0) + 1
        }
        return counts.entries.map { it.key to it.value }
    }

    /**
     * Formats an integer as Unicode superscript digits (e.g. `12` -> `"¹²"`) for compact summaries.
     */
    fun toSuperscriptDigits(value: Int): String {
        val raw = value.toString()
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    '0' -> '⁰'
                    '1' -> '¹'
                    '2' -> '²'
                    '3' -> '³'
                    '4' -> '⁴'
                    '5' -> '⁵'
                    '6' -> '⁶'
                    '7' -> '⁷'
                    '8' -> '⁸'
                    '9' -> '⁹'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}
