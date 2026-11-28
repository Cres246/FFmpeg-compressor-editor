package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleParserTest {

    @Test
    fun parsesStandardSrtWithTurkishUtf8AndHtmlCleanup() {
        val srt = """
            1
            00:00:01,250 --> 00:00:04,500
            <i>Merhaba dünya!</i>
            Şimdi ğ, Ğ, ş, Ş, ı, İ, ö, Ö, ü, Ü, ç, Ç testi.

            2
            00:00:05,000 --> 00:00:08,100
            İkinci satır.
        """.trimIndent()

        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1.25, cues[0].startSec, 0.001)
        assertEquals(4.5, cues[0].endSec, 0.001)
        assertEquals("Merhaba dünya!\nŞimdi ğ, Ğ, ş, Ş, ı, İ, ö, Ö, ü, Ü, ç, Ç testi.", cues[0].text)
        assertEquals(5.0, cues[1].startSec, 0.001)
        assertEquals("İkinci satır.", cues[1].text)
    }

    @Test
    fun decodesWindows1254TurkishAnsiAndUtf8BomCleanly() {
        val turkishLine = "1\n00:00:01,000 --> 00:00:03,000\nÇağdaş güneş ışığı çok güzel!\n"
        val cp1254Bytes = turkishLine.toByteArray(Charset.forName("windows-1254"))
        val decodedAnsi = SubtitleParser.decodeBytesSmart(cp1254Bytes)
        assertEquals(turkishLine, decodedAnsi)

        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + turkishLine.toByteArray(Charsets.UTF_8)
        val decodedBom = SubtitleParser.decodeBytesSmart(utf8BomBytes)
        assertEquals(turkishLine, decodedBom)
    }

    @Test
    fun parsesAssAndWebVttAndStripsOverrideTags() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.50,0:00:13.25,Default,,0,0,0,,{\an8\b1}Üst başlık\Nİkinci satır, virgüllü
        """.trimIndent()

        val assCues = SubtitleParser.parse(ass)
        assertEquals(1, assCues.size)
        assertEquals(10.5, assCues[0].startSec, 0.001)
        assertEquals(13.25, assCues[0].endSec, 0.001)
        assertEquals("Üst başlık\nİkinci satır, virgüllü", assCues[0].text)

        val vtt = """
            WEBVTT

            00:02.500 --> 00:04.750 align:center
            WebVTT satırı
        """.trimIndent()
        val vttCues = SubtitleParser.parse(vtt)
        assertEquals(1, vttCues.size)
        assertEquals(2.5, vttCues[0].startSec, 0.001)
        assertEquals(4.75, vttCues[0].endSec, 0.001)
        assertEquals("WebVTT satırı", vttCues[0].text)
    }

    @Test
    fun generatesValidAssAndSrtWithExactFontAndEscaping() {
        val cues = listOf(
            SubtitleCue(1L, 1.5, 4.25, "Merhaba {test}\nİkinci satır")
        )
        val srt = SubtitleParser.toSrt(cues)
        assertTrue(srt.contains("00:00:01,500 --> 00:00:04,250"))
        assertTrue(srt.contains("Merhaba {test}"))

        val ass = SubtitleParser.toAss(
            cues = cues,
            font = SubtitleFontOption.MONTSERRAT,
            fontSize = SubtitleFontSizeOption.LARGE,
            videoWidth = 1920,
            videoHeight = 1080
        )
        assertTrue(ass.contains("PlayResX: 1920"))
        assertTrue(ass.contains("PlayResY: 1080"))
        assertTrue(ass.contains("Style: Default,Montserrat,"))
        assertTrue(ass.contains("Dialogue: 0,0:00:01.50,0:00:04.25,Default,,0,0,0,,Merhaba \\{test\\}\\Nİkinci satır"))

        val escaped = SubtitleParser.escapeForFfmpegFilterPath("/data/user/0/app/cache/dir:1/sub's.ass")
        assertEquals("/data/user/0/app/cache/dir\\:1/sub\\'s.ass", escaped)
    }

    @Test
    fun binarySearchActiveCueAndVisibleRangeScaleCleanly() {
        val cues = (0 until 1000).map { i ->
            val start = i * 3.0
            SubtitleCue(i + 1L, start, start + 2.0, "Cue #$i")
        }
        assertEquals(-1, SubtitleParser.findActiveCueIndex(cues, 2.5))
        assertEquals(0, SubtitleParser.findActiveCueIndex(cues, 0.5))
        assertEquals(500, SubtitleParser.findActiveCueIndex(cues, 1501.0))
        assertEquals(999, SubtitleParser.findActiveCueIndex(cues, 2998.5))

        val visible = SubtitleParser.findVisibleCueRange(cues, 30.0, 40.0)
        assertFalse(visible.isEmpty())
        assertTrue(visible.first <= 10)
        assertTrue(visible.last >= 13)
        assertTrue(visible.count() <= 10)
    }

    @Test
    fun groupsSubtitleTrackFormatsAndSuperscriptDigits() {
        val tracks = listOf(
            SubtitleTrackInfo(0, 2, "subrip", "tur"),
            SubtitleTrackInfo(1, 3, "srt", "eng"),
            SubtitleTrackInfo(2, 4, "ass", "jpn"),
            SubtitleTrackInfo(3, 5, "subrip", "deu"),
            SubtitleTrackInfo(4, 6, "hdmv_pgs_subtitle", "eng")
        )
        val grouped = SubtitleParser.groupSubtitleTrackFormats(tracks)
        assertEquals(listOf(".srt" to 3, ".ass" to 1, ".pgs" to 1), grouped)
        assertEquals("¹²", SubtitleParser.toSuperscriptDigits(12))
    }
}
