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
        assertEquals((1..11).toList(), numbers)
        assertEquals("Gmail", ProcessingMode.GMAIL_25MB.title)
        assertEquals(3, ProcessingMode.GMAIL_25MB.number)
        assertEquals("Sesi Çıkart", ProcessingMode.EXTRACT_AUDIO.title)
        assertEquals(7, ProcessingMode.EXTRACT_AUDIO.number)
    }

    @Test
    fun telemetryFormatsLiveSizeInDecimalMegabytes() {
        // 15.6 MB = ~15974 KB
        val line156 = FfmpegMath.formatTelemetryLine(
            currentSec = 30.0,
            totalDurationSec = 60.0,
            percent = 50,
            speed = "3.40x",
            sizeKb = 15974L,
            includePercent = false
        )
        assertTrue("Expected 15.6mb in '$line156'", line156.contains("15.6mb"))
        assertFalse("Should no longer emit kb suffix in '$line156'", line156.contains("kb"))

        // Tiny positive write floors cleanly to 0.1mb
        val lineSmall = FfmpegMath.formatTelemetryLine(
            currentSec = 1.0,
            totalDurationSec = 60.0,
            percent = 2,
            speed = "1.20x",
            sizeKb = 32L,
            includePercent = false
        )
        assertTrue("Expected 0.1mb floor in '$lineSmall'", lineSmall.contains("0.1mb"))

        // Zero bytes hides size segment cleanly
        val lineZero = FfmpegMath.formatTelemetryLine(
            currentSec = 0.0,
            totalDurationSec = 60.0,
            percent = 0,
            speed = "...",
            sizeKb = 0L,
            includePercent = false
        )
        assertFalse(lineZero.contains("mb"))
    }

    @Test
    fun audioBitrateOptionsAndWarningsRespectSourceQuality() {
        // 128k lossy source caps quick chips to [64, 128] and caps 256k default to 128k
        assertEquals(
            listOf(64, 128),
            FfmpegMath.resolveAudioBitrateQuickOptions(sourceKbps = 128, isLosslessSource = false)
        )
        assertEquals(
            128,
            FfmpegMath.resolveDefaultAudioBitrate(baseDefaultKbps = 256, sourceKbps = 128, isLosslessSource = false)
        )
        assertFalse(FfmpegMath.isAudioBitrateAboveSource(128, sourceKbps = 128, isLosslessSource = false))
        assertTrue(FfmpegMath.isAudioBitrateAboveSource(192, sourceKbps = 128, isLosslessSource = false))

        // 126k phone/YouTube AAC still keeps 128k chip (+12k tolerance) without warning, warns at 192k
        assertEquals(
            listOf(64, 128),
            FfmpegMath.resolveAudioBitrateQuickOptions(sourceKbps = 126, isLosslessSource = false)
        )
        assertFalse(FfmpegMath.isAudioBitrateAboveSource(128, sourceKbps = 126, isLosslessSource = false))
        assertTrue(FfmpegMath.isAudioBitrateAboveSource(192, sourceKbps = 126, isLosslessSource = false))

        // Non-standard 96k source injects 96 into chips and caps default to 96
        assertEquals(
            listOf(64, 96),
            FfmpegMath.resolveAudioBitrateQuickOptions(sourceKbps = 96, isLosslessSource = false)
        )
        assertEquals(
            96,
            FfmpegMath.resolveDefaultAudioBitrate(baseDefaultKbps = 128, sourceKbps = 96, isLosslessSource = false)
        )
        assertTrue(FfmpegMath.isAudioBitrateAboveSource(128, sourceKbps = 96, isLosslessSource = false))

        // Very low 48k source yields [48]
        assertEquals(
            listOf(48),
            FfmpegMath.resolveAudioBitrateQuickOptions(sourceKbps = 48, isLosslessSource = false)
        )

        // Lossless FLAC keeps full options up to maxKbps and never warns
        assertEquals(
            listOf(64, 128, 192, 256, 320),
            FfmpegMath.resolveAudioBitrateQuickOptions(sourceKbps = 900, isLosslessSource = true, maxKbps = 320)
        )
        assertFalse(FfmpegMath.isAudioBitrateAboveSource(768, sourceKbps = 900, isLosslessSource = true))

        // Positive out-of-range inputs clamp cleanly instead of resetting to default
        assertEquals(320, FfmpegMath.normalizeAudioBitrate("512", defaultKbps = 128, maxKbps = 320))
        assertEquals(32, FfmpegMath.normalizeAudioBitrate("12", defaultKbps = 128, maxKbps = 768))
    }

    @Test
    fun antiUpscalingTurkishCommasAndCustomSizeDefault() {
        // 720p source targeting 1080p must NOT upscale
        assertNull(FfmpegMath.buildScaleFilter(1280, 720, ResolutionOption.P1080, false))
        // Odd 720p source targeting 1080p falls back to EVEN_VF instead of upscaling
        assertEquals(
            "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            FfmpegMath.buildScaleFilter(1279, 719, ResolutionOption.P1080, true)
        )

        // Turkish decimal commas in trim inputs normalize to dots cleanly
        val trimCfg = ModeConfigState(
            mode = ProcessingMode.TRIM_VIDEO,
            startTimeInput = "10,5",
            endTimeInput = "25,75"
        )
        val (startStr, endStr, durSec) = FfmpegMath.resolveTrimRange(trimCfg, 60.0)
        assertEquals("10.5", startStr)
        assertEquals("25.75", endStr)
        assertEquals(15.25, durSec!!, 0.001)

        // Blank CUSTOM_SIZE input unifies on 8 MB default across UI & backend
        val fakeMedia = MediaInfo(
            uri = Uri.EMPTY,
            fileName = "clip.mp4",
            baseName = "clip",
            extension = "mp4",
            sizeBytes = 30_000_000L,
            durationSec = 30.0,
            videoCodec = "h264",
            width = 1280,
            height = 720,
            pixFmt = "yuv420p",
            audioCodec = "aac",
            audioBitrateKbps = 128
        )
        val blankCustom = ModeConfigState(mode = ProcessingMode.CUSTOM_SIZE, targetMbInput = "")
        assertEquals("clip_H264_8MB.mp4", FfmpegMath.computeOutputFileName(fakeMedia, blankCustom))
    }

    @Test
    fun oneAndTwoMbTargetsHaveExtraSafetyNotchWhileLargerTargetsStayUnchanged() {
        // 1 MB, 30s, 64k audio => 0.91 factor: floor((1 * 8000 * 0.91 / 30) - 64) = floor(242.66 - 64) = 178 kbps
        assertEquals(178, FfmpegMath.calculateTwoPassVideoBitrateKbps(1.0, 30.0, 64))
        // 2 MB, 60s, 64k audio => 0.935 factor: floor((2 * 8000 * 0.935 / 60) - 64) = floor(249.33 - 64) = 185 kbps
        assertEquals(185, FfmpegMath.calculateTwoPassVideoBitrateKbps(2.0, 60.0, 64))
        // > 2 MB targets stay 100% untouched at 0.96
        assertEquals(704, FfmpegMath.calculateTwoPassVideoBitrateKbps(3.0, 30.0, 64))
        assertEquals(1152, FfmpegMath.calculateTwoPassVideoBitrateKbps(20.0, 120.0, 128))
    }

    @Test
    fun applyAutoScannedKeyframesSnapsStartBelowAndEndAboveWithoutClobberingManualPick() {
        val baseCfg = ModeConfigState(
            mode = ProcessingMode.TRIM_VIDEO,
            startTimeInput = "00:10.0",
            endTimeInput = "00:20.0"
        )
        val startScan = com.example.domain.KeyframePointResult(
            targetSec = 10.0,
            prevKf = 8.0,
            nextKf = 12.0,
            recommendedRawSec = 8.005,
            recommendedDisplay = "00:08.0",
            nearbyKeyframes = listOf(8.0, 12.0)
        )
        val endScan = com.example.domain.KeyframePointResult(
            targetSec = 20.0,
            prevKf = 18.0,
            nextKf = 22.0,
            recommendedRawSec = 22.0,
            recommendedDisplay = "00:22.0",
            nearbyKeyframes = listOf(18.0, 22.0)
        )

        val snapped = FfmpegMath.applyAutoScannedKeyframes(baseCfg, 60.0, startScan, endScan)
        val (effStart, effEnd, clipDur) = FfmpegMath.resolveTrimRange(snapped, 60.0)
        assertEquals("8.005", effStart)
        assertEquals("22.000", effEnd)
        assertEquals(13.995, clipDur!!, 0.001)

        // Manual start keyframe selection must be preserved even when auto-scan supplies startScan
        val manualStart = baseCfg.copy(selectedStartKfSec = 12.0)
        val snappedManual = FfmpegMath.applyAutoScannedKeyframes(manualStart, 60.0, startScan, endScan)
        val (manStart, manEnd, _) = FfmpegMath.resolveTrimRange(snappedManual, 60.0)
        assertEquals("12.005", manStart)
        assertEquals("22.000", manEnd)
    }

    @Test
    fun turkishModeTitlesUseDonusturAndEnglishRemainsUntouched() {
        assertEquals("Formatı Dönüştür", com.example.domain.TurkishStrings.modeRemuxTitle)
        assertEquals("Formatı Dönüştür", com.example.domain.TurkishStrings.modeRemuxShortTitle)
        assertEquals("Sadece Sesi Dönüştür", com.example.domain.TurkishStrings.modeAudioOnlyTitle)
        assertEquals("Sadece Görüntüyü Dönüştür", com.example.domain.TurkishStrings.modeVideoOnlyTitle)
        assertEquals("Görüntüyü + Sesi Dönüştür", com.example.domain.TurkishStrings.modeVideoAndAudioTitle)
        assertEquals("Oynat", com.example.domain.TurkishStrings.actionPlayOutput)
        assertEquals("Editle", com.example.domain.TurkishStrings.actionEditOutput)

        // English mode titles remain unchanged
        assertEquals("Change Format", com.example.domain.EnglishStrings.modeRemuxTitle)
        assertEquals("Re-encode Audio Only", com.example.domain.EnglishStrings.modeAudioOnlyTitle)
        assertEquals("Re-encode Video Only", com.example.domain.EnglishStrings.modeVideoOnlyTitle)
        assertEquals("Re-encode Video + Audio", com.example.domain.EnglishStrings.modeVideoAndAudioTitle)
        assertEquals("Play", com.example.domain.EnglishStrings.actionPlayOutput)
        assertEquals("Edit", com.example.domain.EnglishStrings.actionEditOutput)
    }
}
