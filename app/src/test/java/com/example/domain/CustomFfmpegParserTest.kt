package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFfmpegParserTest {

    private fun buildSamplePlan(
        paste: String,
        inputTokenProvider: () -> String
    ): CustomCommandPlan = CustomFfmpegParser.buildPlan(
        rawCommand = paste,
        mediaFileName = "VID_20261123_194200.mkv",
        mediaExtension = "mkv",
        mediaDurationSec = 60.0,
        workDirPath = "/cache/work",
        defaultOutputFileName = "cikti.mp4",
        inputTokenProvider = inputTokenProvider
    )

    @Test
    fun parsesChatGptMarkdownTwoPassWithMp4NullAndProse() {
        val paste = """
            İşte videonuzu 2 geçişte sıkıştıran FFmpeg komutu:
            ```bash
            # Pass 1: Video analizi
            $ ffmpeg -y -i input.mp4 \
              -c:v libx264 -b:v 2600k -pass 1 -an -movflags +faststart -f mp4 /dev/null && \
            # Pass 2: Final encode
            $ ffmpeg -i input.mp4 \
              -c:v libx264 -b:v 2600k -pass 2 -c:a aac -b:a 128k output.mp4 # final çıktı
            ```
            Bu komut 2 geçişte işlem yapar.
        """.trimIndent()

        var tokenIdx = 0
        val plan = buildSamplePlan(paste) { "saf_${++tokenIdx}" }

        assertEquals(2, plan.steps.size)
        assertTrue(plan.adaptations.strippedMarkdownOrProse)
        assertTrue(plan.adaptations.joinedMultiline)
        assertTrue(plan.adaptations.autoConfiguredTwoPassLogs)
        assertEquals(2, plan.adaptations.autoBoundInputsCount)

        val s1 = plan.steps[0].args
        assertTrue(s1.contains("saf_1"))
        assertTrue(s1.contains("-passlogfile"))
        assertTrue(s1.contains("/cache/work/ffmpeg2pass"))
        // `-f mp4` on `/dev/null` must be rewritten to `-f null` and `-movflags` removed
        assertFalse(s1.contains("mp4"))
        assertFalse(s1.contains("-movflags"))
        assertEquals("/dev/null", s1.last())
        assertTrue(plan.steps[0].isNullOutputOnly)

        val s2 = plan.steps[1].args
        assertEquals("-y", s2.first())
        assertTrue(s2.contains("saf_2"))
        assertTrue(s2.contains("-passlogfile"))
        assertEquals("/cache/work/output.mp4", s2.last())
        assertEquals("output.mp4", plan.primaryFinalOutputName)
    }

    @Test
    fun parsesWindowsCmdTwoPassX265WithCaretNulPathsAndDelCleanup() {
        val paste = """
            ffmpeg -y -i "C:\Users\Ali\Videos\my clip.mp4" ^
              -c:v libx265 -b:v 1800k -x265-params pass=1 -an -f mp4 NUL
            ffmpeg -i "C:\Users\Ali\Videos\my clip.mp4" ^
              -c:v libx265 -b:v 1800k -x265-params pass=2 -c:a aac -b:a 128k "C:\Users\Ali\Desktop\final_video.mp4"
            del x265_2pass.log*
        """.trimIndent()

        var tokenIdx = 0
        val plan = buildSamplePlan(paste) { "saf_${++tokenIdx}" }

        assertEquals(2, plan.steps.size)
        assertTrue(plan.adaptations.mappedWindowsNull)
        assertTrue(plan.adaptations.autoConfiguredTwoPassLogs)
        assertEquals(1, plan.adaptations.ignoredShellCleanupCount)

        val s1 = plan.steps[0].args
        assertEquals("/dev/null", s1.last())
        assertTrue(s1.any { it.contains("pass=1") && it.contains("stats=/cache/work/x265_2pass.log") })

        val s2 = plan.steps[1].args
        assertTrue(s2.any { it.contains("pass=2") && it.contains("stats=/cache/work/x265_2pass.log") })
        assertEquals("/cache/work/final_video.mp4", s2.last())
        assertEquals("final_video.mp4", plan.primaryFinalOutputName)
    }

    @Test
    fun parsesTwoStepPaletteGifPreservingIntermediateInput() {
        val paste = """
            ffmpeg -i video.mov -vf "fps=15,scale=480:-1:flags=lanczos,palettegen" palette.png
            ffmpeg -i video.mov -i palette.png -filter_complex "fps=15,scale=480:-1:flags=lanczos[x];[x][1:v]paletteuse" anim.gif
        """.trimIndent()

        var tokenIdx = 0
        val plan = buildSamplePlan(paste) { "saf_${++tokenIdx}" }

        assertEquals(2, plan.steps.size)
        assertTrue("palette.png" in plan.consumedIntermediateFiles)
        assertEquals("anim.gif", plan.primaryFinalOutputName)

        val s2 = plan.steps[1].args
        val firstInputIdx = s2.indexOf("-i")
        val secondInputIdx = s2.lastIndexOf("-i")
        assertEquals("saf_2", s2[firstInputIdx + 1])
        assertEquals("/cache/work/palette.png", s2[secondInputIdx + 1])
        assertEquals("/cache/work/anim.gif", s2.last())
    }

    @Test
    fun parsesShellVariablesLavfiAndRedirections() {
        val paste = """
            VBIT="1400k"
            OUT="clean_out.mp4"
            ffmpeg -i source.mp4 -f lavfi -i anullsrc=r=44100:cl=stereo -c:v libx264 -b:v ${'$'}VBIT -shortest "${'$'}OUT" 2>&1
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertTrue(args.contains("1400k"))
        assertTrue(args.contains("anullsrc=r=44100:cl=stereo"))
        assertFalse(args.contains("2>&1"))
        assertEquals("/cache/work/clean_out.mp4", args.last())
    }

    @Test
    fun parsesFlagsOnlySnippetByAutoAddingInputAndOutput() {
        val paste = "-c:v libx264 -crf 23 -vf scale=720:-2"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("saf_1"))
        assertEquals("/cache/work/cikti.mp4", args.last())
        assertEquals("cikti.mp4", plan.adaptations.autoAddedOutputFileName)
    }

    @Test
    fun parsesMultipleOutputsAndTrimsDurationAccurately() {
        val paste = "ffmpeg -ss 00:00:10 -to 00:00:25 -i input.mp4 -an -c:v copy v_only.mp4 -vn -c:a libmp3lame a_only.mp3"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertEquals(listOf("v_only.mp4", "a_only.mp3"), plan.steps[0].outputFileNames)
        assertEquals(15.0, plan.steps[0].expectedDurationSec ?: -1.0, 0.01)
    }

    @Test
    fun stripsRawAiProseMentioningFfmpegWithoutMarkdownFences() {
        val paste = """
            İşte videonuzu küçülten FFmpeg komutu:
            1. Aşama (Pass 1):
            time ffmpeg -y -i <input.mp4> -c:v libx264 -b:v 1500k -pass 1 -an -f mp4 /dev/null
            2. Aşama (Pass 2):
            ffmpeg -i [input.mp4] -c:v libx264 -b:v 1500k -pass 2 -c:a aac [kucuk_video.mp4]
            Bu FFmpeg komutu en iyi kaliteyi verir.
        """.trimIndent()

        var idx = 0
        val plan = buildSamplePlan(paste) { "saf_${++idx}" }

        assertEquals(2, plan.steps.size)
        assertTrue(plan.adaptations.strippedMarkdownOrProse)
        assertEquals("kucuk_video.mp4", plan.primaryFinalOutputName)
        assertEquals("/cache/work/kucuk_video.mp4", plan.steps[1].args.last())
    }

    @Test
    fun handlesCurlyQuotesAndTypographicDashes() {
        val paste = "ffmpeg –i “my video.mp4” —vf “scale=1280:-2” –c:v libx264 –crf 24 “clean.mp4”"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("saf_1"))
        assertTrue(args.contains("-vf"))
        assertTrue(args.contains("scale=1280:-2"))
        assertTrue(args.contains("-crf"))
        assertEquals("/cache/work/clean.mp4", args.last())
    }

    @Test
    fun ignoresMarkdownBulletExplanationsAfterCommand() {
        val paste = """
            ffmpeg -i clip.mp4 -c:v libx264 -crf 20 out.mp4
            - Uses H.264 for universal playback
            - Keeps crisp visual quality
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertFalse(args.contains("-"))
        assertFalse(args.contains("Uses"))
        assertEquals("/cache/work/out.mp4", args.last())
    }

    @Test
    fun previewModeDetectsPassLogfileRewriteAccurately() {
        val paste = "ffmpeg -y -i clip.mp4 -c:v libx264 -b:v 1500k -pass 1 -passlogfile /tmp/custom -an -f null /dev/null"
        val previewPlan = CustomFfmpegParser.buildPlan(
            rawCommand = paste,
            mediaFileName = "clip.mp4",
            mediaExtension = "mp4",
            mediaDurationSec = 60.0,
            workDirPath = "",
            defaultOutputFileName = "cikti.mp4",
            inputTokenProvider = { "\$IN" },
            isPreview = true
        )
        assertTrue(previewPlan.adaptations.autoConfiguredTwoPassLogs)
        assertTrue(previewPlan.steps[0].args.contains("custom"))
    }
}
