package com.example

import com.example.domain.BitDepthOption
import com.example.domain.FfmpegMath
import com.example.domain.FpsOption
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.ResolutionOption
import com.example.domain.VideoCodecOption
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FfmpegMathTest {

    @Test
    fun twoPassBitrateFormulaMatchesScript() {
        // 20 MB, 120s, 128k audio => floor((20 * 8000 * 0.96 / 120) - 128) = floor(1280 - 128) = 1152
        assertEquals(1152, FfmpegMath.calculateTwoPassVideoBitrateKbps(20.0, 120.0, 128))
        // Minimum clamp to 80 kbps
        assertEquals(80, FfmpegMath.calculateTwoPassVideoBitrateKbps(1.0, 3600.0, 128))
        assertTrue(FfmpegMath.isBitrateWarningLow(120))
        assertFalse(FfmpegMath.isBitrateWarningLow(121))
    }

    @Test
    fun threeWayAspectRatioScaleAndOddPixelGuard() {
        // Portrait 1080x1920 -> 720p => scale=720:-2
        assertEquals("scale=720:-2", FfmpegMath.buildScaleFilter(1080, 1920, ResolutionOption.P720, false))
        // Cinema ultrawide 3840x1600 -> 1080p => scale=1920:-2 (prevents 2592 width overflow)
        assertEquals("scale=1920:-2", FfmpegMath.buildScaleFilter(3840, 1600, ResolutionOption.P1080, false))
        // Standard 16:9 1920x1080 -> 720p => scale=-2:720
        assertEquals("scale=-2:720", FfmpegMath.buildScaleFilter(1920, 1080, ResolutionOption.P720, false))
        // Odd dimensions with Original => EVEN_VF
        assertEquals(
            "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            FfmpegMath.buildScaleFilter(1079, 1920, ResolutionOption.ORIGINAL, true)
        )
        // Even dimensions with Original => null
        assertNull(FfmpegMath.buildScaleFilter(1920, 1080, ResolutionOption.ORIGINAL, false))
    }

    @Test
    fun keyframeParserComputesStartBufferAndEndPts() {
        val sampleProbe = """
            pts_time=54.200000|dts_time=54.100000|flags=K_
            pts_time=58.000000|dts_time=57.900000|flags=K_
            pts_time=59.500000|dts_time=59.400000|flags=__
            pts_time=62.400000|dts_time=62.300000|flags=K_
        """.trimIndent()

        val startRes = FfmpegMath.parseKeyframePackets(sampleProbe, 60.0, isStart = true)
        assertEquals(58.0, startRes.prevKf!!, 0.0001)
        assertEquals(62.4, startRes.nextKf!!, 0.0001)
        // Start snaps to prev_kf + 0.005 (5ms buffer)
        assertEquals(58.005, startRes.recommendedRawSec!!, 0.0001)
        assertEquals("00:58.0", startRes.recommendedDisplay)

        val endRes = FfmpegMath.parseKeyframePackets(sampleProbe, 60.0, isStart = false)
        // End snaps to raw next_kf
        assertEquals(62.4, endRes.recommendedRawSec!!, 0.0001)
        assertEquals("01:02.4", endRes.recommendedDisplay)
    }

    @Test
    fun originalAudioExtensionMappingMatchesScript() {
        assertEquals("m4a", FfmpegMath.resolveOriginalAudioExtension("aac"))
        assertEquals("opus", FfmpegMath.resolveOriginalAudioExtension("opus"))
        assertEquals("ogg", FfmpegMath.resolveOriginalAudioExtension("vorbis"))
        assertEquals("wav", FfmpegMath.resolveOriginalAudioExtension("pcm_s16le"))
        assertEquals("mka", FfmpegMath.resolveOriginalAudioExtension("dts"))
    }

    @Test
    fun customCommandChainSplitsOutsideQuotes() {
        val chain = FfmpegMath.parseCustomCommandChain(
            """ffmpeg -y -i "${'$'}IN" -c:v libx264 -b:v 2000k -pass 1 -an -f null /dev/null && ffmpeg -y -i "${'$'}IN" -c:v libx264 -b:v 2000k -pass 2 -c:a aac "out file.mp4""""
        )
        assertEquals(2, chain.size)
        assertEquals("-y", chain[0][0])
        assertEquals("\$IN", chain[0][2])
        assertEquals("out file.mp4", chain[1].last())
    }

    @Test
    fun h265TwoPassUsesX265ParamsAndHvc1Tag() {
        val fakeMedia = MediaInfo(
            uri = Uri.EMPTY,
            fileName = "clip.mkv",
            baseName = "clip",
            extension = "mkv",
            sizeBytes = 50_000_000,
            durationSec = 60.0,
            videoCodec = "hevc",
            width = 1920,
            height = 1080,
            pixFmt = "yuv420p10le",
            audioCodec = "opus"
        )
        val cfg = ModeConfigState(
            mode = ProcessingMode.DISCORD_20MB,
            codec = VideoCodecOption.H265,
            bitDepth = BitDepthOption.BIT_10,
            fps = FpsOption.FPS_30,
            resolution = ResolutionOption.P1080,
            preset = PresetOption.MEDIUM,
            audioBitrateInput = "128"
        )
        val steps = FfmpegMath.buildFfmpegSteps(
            media = fakeMedia,
            config = cfg,
            inputTokenProvider = { "saf:1" },
            outputPath = "/tmp/out.mp4",
            passLogPrefix = "/tmp/ff2pass"
        )
        assertEquals(2, steps.size)
        assertTrue(steps[0].args.contains("log-level=warning:pools=+:pass=1:stats=/tmp/ff2pass.log"))
        assertTrue(steps[1].args.contains("-tag:v"))
        assertTrue(steps[1].args.contains("hvc1"))
        assertTrue(steps[1].args.contains("yuv420p10le"))
        assertEquals("clip_H265_10bit_20MB.mp4", FfmpegMath.computeOutputFileName(fakeMedia, cfg))
    }

    @Test
    fun cancelActiveRunIgnoresLateProgressUpdates() {
        val runId = com.example.service.EncodingJobController.beginNewRun()
        assertTrue(com.example.service.EncodingJobController.isRunActive(runId))

        com.example.service.EncodingJobController.cancelActiveRun()
        assertFalse(com.example.service.EncodingJobController.isRunActive(runId))
        assertEquals(com.example.domain.EncodingState.Idle, com.example.service.EncodingJobController.state.value)

        // Late FFmpegKit callback arriving after cancel must NEVER reopen Running state
        com.example.service.EncodingJobController.updateStateIfActive(
            runId,
            com.example.domain.EncodingState.Running(
                modeTitle = "01 · Discord",
                outputFileName = "out.mp4",
                passIndex = 1,
                totalPasses = 2,
                statusMessage = "late",
                currentTimeSec = 10.0,
                totalDurationSec = 60.0,
                percent = 16,
                speed = "1.0x",
                sizeKb = 500L,
                logs = emptyList(),
                summaryBanner = ""
            )
        )
        assertEquals(com.example.domain.EncodingState.Idle, com.example.service.EncodingJobController.state.value)
    }

    @Test
    fun centeredKeyframesIncludesBeforeAndAfterTarget() {
        // Dense 1-second GOP gallery video from 3s to 27s with target = 15.0s
        val allKeyframes = (3..27).map { it.toDouble() }
        val centered = FfmpegMath.selectCenteredKeyframes(allKeyframes, targetSec = 15.0, maxCount = 12)
        assertEquals(12, centered.size)
        assertTrue(centered.contains(12.0))
        assertTrue(centered.contains(13.0))
        assertTrue(centered.contains(14.0))
        assertTrue(centered.contains(15.0))
        assertTrue(centered.contains(16.0))
        assertTrue(centered.contains(17.0))
        assertTrue(centered.contains(18.0))
    }

    @Test
    fun processingModesHaveSequentialNumbers() {
        val numbers = ProcessingMode.entries.map { it.number }
        assertEquals((1..10).toList(), numbers)
        assertEquals("Sesi Çıkart", ProcessingMode.EXTRACT_AUDIO.title)
        assertEquals(6, ProcessingMode.EXTRACT_AUDIO.number)
    }
}
