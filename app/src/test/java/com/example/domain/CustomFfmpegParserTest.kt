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

    @Test
    fun extractsInlineBacktickCommandFromProse() {
        val paste = "Videoyu küçültmek için `ffmpeg -i video.mp4 -c:v libx264 -crf 25 kucuk.mp4` komutunu çalıştırabilirsin."
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertTrue(plan.adaptations.strippedMarkdownOrProse)
        assertEquals("kucuk.mp4", plan.primaryFinalOutputName)
        assertEquals("/cache/work/kucuk.mp4", plan.steps[0].args.last())
    }

    @Test
    fun skipsNonFfmpegInstallCodeBlockAndPicksFfmpegBlock() {
        val paste = """
            Önce FFmpeg kur:
            ```bash
            brew install ffmpeg
            ```
            Sonra dönüştür:
            ~~~bash
            ffmpeg -i input.mp4 -c:v libx264 -crf 22 sonuc.mp4
            ~~~
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertTrue(plan.adaptations.strippedMarkdownOrProse)
        assertEquals("sonuc.mp4", plan.primaryFinalOutputName)
        assertFalse(plan.steps[0].args.contains("brew"))
    }

    @Test
    fun adaptsDesktopNvencCudaAndFdkAacCommandToAndroidGpl() {
        val paste = """
            ffmpeg -hwaccel cuda -hwaccel_output_format cuda -i input.mp4 \
              -vf "hwupload_cuda,scale_cuda=1280:-2,hwdownload" \
              -c:v h264_nvenc -preset p5 -rc vbr -cq 22 -spatial-aq 1 \
              -c:a libfdk_aac -vbr 4 output.mp4
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertTrue(plan.adaptations.adaptedDesktopCodecCount > 0)
        val args = plan.steps[0].args
        assertFalse(args.contains("-hwaccel"))
        assertFalse(args.contains("-hwaccel_output_format"))
        assertFalse(args.contains("-rc"))
        assertFalse(args.contains("-spatial-aq"))
        assertFalse(args.contains("-cq"))
        assertFalse(args.contains("-vbr"))
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("slow"))
        assertTrue(args.contains("-crf"))
        assertTrue(args.contains("22"))
        assertTrue(args.contains("scale=1280:-2"))
        assertTrue(args.contains("aac"))
        assertTrue(args.contains("192k"))
        assertEquals("/cache/work/output.mp4", args.last())
    }

    @Test
    fun repairsMobileKeyboardSpacesCasingDoubleDashEqualsTurkishCommasAndUnits() {
        val paste = "\u200BFFmpeg - I input.mp4 --C: v libx264 -CRF=23,5 -Preset=fast -fps 29,97 -b: a 128 kbps -ss 00: 00: 05,5 -faststart out.mp4."
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertTrue(plan.adaptations.fixedSyntaxOrTyposCount > 0)
        val args = plan.steps[0].args
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("saf_1"))
        assertTrue(args.contains("-c:v"))
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("-crf"))
        assertTrue(args.contains("23.5"))
        assertTrue(args.contains("-preset"))
        assertTrue(args.contains("fast"))
        assertTrue(args.contains("-r"))
        assertTrue(args.contains("29.97"))
        assertTrue(args.contains("-b:a"))
        assertTrue(args.contains("128k"))
        assertTrue(args.contains("-ss"))
        assertTrue(args.contains("00:00:05.5"))
        assertTrue(args.contains("-movflags"))
        assertTrue(args.contains("+faststart"))
        assertEquals("/cache/work/out.mp4", args.last())
        assertEquals(54.5, plan.steps[0].expectedDurationSec ?: -1.0, 0.05)
    }

    @Test
    fun handlesOmittedInputFlagUnquotedSpacesAndUnquotedFilterCommas() {
        val paste = "ffmpeg my raw video.mp4 -vf scale=1280:-2, fps=30 -c:v x264 -o yeni sonuc.mp4"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertEquals(listOf("-y", "-i", "saf_1", "-vf", "scale=1280:-2,fps=30", "-c:v", "libx264", "/cache/work/yeni sonuc.mp4"), args)
        assertEquals("yeni sonuc.mp4", plan.primaryFinalOutputName)
    }

    @Test
    fun recoversFromMissingFilenameAfterInputFlagAndUnclosedQuote() {
        val paste = """ffmpeg -i -vf "scale=720:-2,fps=30 -c:v libx264 -crf 24 out.mp4"""
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertEquals(listOf("-y", "-i", "saf_1", "-vf", "scale=720:-2,fps=30", "-c:v", "libx264", "-crf", "24", "/cache/work/out.mp4"), args)
    }

    @Test
    fun parsesBashForLoopWithFilenameStemExpansion() {
        val paste = """for f in *.mp4; do ffmpeg -i "${'$'}f" -c:v libx264 -crf 23 "${'$'}{f%.*}_small.mp4"; done"""
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertEquals("VID_20261123_194200_small.mp4", plan.primaryFinalOutputName)
        assertEquals("/cache/work/VID_20261123_194200_small.mp4", plan.steps[0].args.last())
    }

    @Test
    fun preservesNegativeStreamSpecifiersAndSseofValues() {
        val paste = "ffmpeg -sseof -00:00:10 -i input.mp4 -map 0 -map -0:s? -map -0:a -c:v copy clip.mp4"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        val sseofIdx = args.indexOf("-sseof")
        assertEquals("-00:00:10", args[sseofIdx + 1])
        assertTrue(args.contains("-0:s?"))
        assertTrue(args.contains("-0:a"))
        assertEquals("/cache/work/clip.mp4", args.last())
    }

    @Test
    fun preservesUnquotedFilterComplexSemicolon() {
        val paste = "ffmpeg -i in.mp4 -filter_complex [0:v]fps=15,scale=480:-1[x];[x]split[a][b];[a]palettegen[p];[b][p]paletteuse out.gif"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        val fcIdx = args.indexOf("-filter_complex")
        assertEquals("[0:v]fps=15,scale=480:-1[x];[x]split[a][b];[a]palettegen[p];[b][p]paletteuse", args[fcIdx + 1])
        assertEquals("/cache/work/out.gif", args.last())
    }

    @Test
    fun injectsMissingNullMuxerBeforeDevNullAndSmartExtensionForAudio() {
        val paste = """
            ffmpeg -i input.mp4 -c:v libx264 -b:v 1500k -pass 1 -an /dev/null
            ffmpeg -i input.mp4 -vn -c:a mp3 -b:a 320kbps
        """.trimIndent()

        var idx = 0
        val plan = buildSamplePlan(paste) { "saf_${++idx}" }

        assertEquals(2, plan.steps.size)
        val s1 = plan.steps[0].args
        assertTrue(s1.contains("-f"))
        assertEquals("null", s1[s1.indexOf("-f") + 1])
        assertEquals("/dev/null", s1.last())

        val s2 = plan.steps[1].args
        assertTrue(s2.contains("libmp3lame"))
        assertTrue(s2.contains("320k"))
        assertEquals("/cache/work/cikti.mp3", s2.last())
    }

    @Test
    fun repairsMissingOutputExtensionAndUnresolvedOutputVariable() {
        val planNoExt = buildSamplePlan("ffmpeg -i in.mp4 -c:v libx264 -crf 23 sonuc") { "saf_1" }
        assertEquals("/cache/work/sonuc.mp4", planNoExt.steps[0].args.last())

        val planUnresolvedVar = buildSamplePlan("""ffmpeg -i "${'$'}INPUT" -c:v libx264 -crf 23 "${'$'}OUTPUT"""") { "saf_1" }
        assertEquals("/cache/work/cikti.mp4", planUnresolvedVar.steps[0].args.last())
    }

    @Test
    fun stripsAiMobileCopyChromeProsePrefixAndBlankLineInsideContinuation() {
        val paste = """
            bash
            Copy code
            1. Adım: **ffmpeg** -i clip.mp4 \

              -c:v libx265 \
              -crf 24 \
              result.mp4 // sıkıştırılmış video
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        assertTrue(plan.adaptations.strippedMarkdownOrProse)
        assertTrue(plan.adaptations.joinedMultiline)
        val args = plan.steps[0].args
        assertTrue(args.contains("libx265"))
        assertTrue(args.contains("24"))
        assertEquals("/cache/work/result.mp4", args.last())
    }

    @Test
    fun parsesWindowsQuotedSetVariablesPowerShellAndDuplicateFfmpegPrefix() {
        val paste = """
            set "BIT=2,5 mbps"
            PS C:\Users\Ali> & ffmpeg ffmpeg -i video.mp4 -c:v h265 -b:v %BIT% "%~n1_h265.mp4"
        """.trimIndent()

        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertTrue(args.contains("libx265"))
        assertTrue(args.contains("2.5M"))
        assertEquals("/cache/work/VID_20261123_194200_h265.mp4", args.last())
    }

    @Test
    fun preservesExplicitNegativeMovflagsAndZeroArgFlagsWithoutSwallowingOutput() {
        val paste = "ffmpeg -i input.mp4 -c copy -movflags -faststart -sn -dn -y clean.mp4"
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        val mfIdx = args.indexOf("-movflags")
        assertEquals("-faststart", args[mfIdx + 1])
        assertEquals("/cache/work/clean.mp4", args.last())
    }

    @Test
    fun handlesUnquotedWindowsPathsGluedQuotesAndStrayCommas() {
        val paste = """ffmpeg -i"C:\Users\Ali\clip.mp4" -vf"scale=720:-2" -crf 23, -r 30, C:\Users\Ali\Desktop\final_win.mp4"""
        val plan = buildSamplePlan(paste) { "saf_1" }

        assertEquals(1, plan.steps.size)
        val args = plan.steps[0].args
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("saf_1"))
        assertTrue(args.contains("scale=720:-2"))
        assertEquals("23", args[args.indexOf("-crf") + 1])
        assertEquals("30", args[args.indexOf("-r") + 1])
        assertEquals("/cache/work/final_win.mp4", args.last())
        assertEquals("final_win.mp4", plan.primaryFinalOutputName)
    }
}
