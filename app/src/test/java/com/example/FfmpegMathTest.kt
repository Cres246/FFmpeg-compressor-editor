package com.example

import com.example.domain.BitDepthOption
import com.example.domain.FfmpegMath
import com.example.domain.FpsOption
import com.example.domain.KeyframePointResult
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.PresetOption
import com.example.domain.ProcessingMode
import com.example.domain.ResolutionOption
import com.example.domain.TrimCutStrategy
import com.example.domain.TrimScanState
import com.example.domain.TrimSubMode
import com.example.domain.TrimTimeChoice
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
        // Start uses adaptive MKV/B-frame-safe seek probe (+0.45s inside [58.0, 62.4))
        assertEquals(58.45, startRes.recommendedRawSec!!, 0.0001)
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
            preset = PresetOption.FAST,
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
        // Seek probe is safely inside [8.0, 12.0) (+0.45s) while clipDur stays exact 22.0 - 8.0 = 14.0s
        assertEquals("8.450", effStart)
        assertEquals("22.000", effEnd)
        assertEquals(14.0, clipDur!!, 0.001)

        // Manual start keyframe selection must be preserved even when auto-scan supplies startScan
        val manualStart = baseCfg.copy(selectedStartKfSec = 12.0)
        val snappedManual = FfmpegMath.applyAutoScannedKeyframes(manualStart, 60.0, startScan, endScan)
        val (manStart, manEnd, manDur) = FfmpegMath.resolveTrimRange(snappedManual, 60.0)
        assertEquals("12.350", manStart)
        assertEquals("22.000", manEnd)
        assertEquals(10.0, manDur!!, 0.001)
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

    @Test
    fun adaptiveTwoPassLeavesNormalEncodesUntouchedAndProtectsStarvedTiers() {
        // Normal encode (60s 1080p30 -> 20 MB): Tier 0, 100% identical to baseline formula
        val normalBase = FfmpegMath.calculateTwoPassVideoBitrateKbps(20.0, 60.0, 128)
        val normalPlan = FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = 20.0,
            durationSec = 60.0,
            audioBitrateKbps = 128,
            codec = VideoCodecOption.H264,
            width = 1920,
            height = 1080,
            resolution = ResolutionOption.ORIGINAL,
            fpsOption = FpsOption.ORIGINAL,
            sourceFps = 30.0
        )
        assertEquals(0, normalPlan.stress.tier)
        assertEquals(normalBase, normalPlan.videoBitrateKbps)
        assertEquals(128, normalPlan.audioBitrateKbps)

        // User followed warning (90s 720p30 -> 10 MB): stays Tier 0, 100% untouched
        val heededBase = FfmpegMath.calculateTwoPassVideoBitrateKbps(10.0, 90.0, 128)
        val heededPlan = FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = 10.0,
            durationSec = 90.0,
            audioBitrateKbps = 128,
            codec = VideoCodecOption.H264,
            width = 1920,
            height = 1080,
            resolution = ResolutionOption.P720,
            fpsOption = FpsOption.FPS_30,
            sourceFps = 30.0
        )
        assertEquals(0, heededPlan.stress.tier)
        assertEquals(heededBase, heededPlan.videoBitrateKbps)

        // Tier 1: User ignored low-bitrate warning and kept 1080p on 90s -> 10 MB (1 notch lower)
        val tier1Plan = FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = 10.0,
            durationSec = 90.0,
            audioBitrateKbps = 128,
            codec = VideoCodecOption.H264,
            width = 1920,
            height = 1080,
            resolution = ResolutionOption.ORIGINAL,
            fpsOption = FpsOption.ORIGINAL,
            sourceFps = 30.0
        )
        assertEquals(1, tier1Plan.stress.tier)
        assertTrue(tier1Plan.videoBitrateKbps < heededBase)
        assertEquals(128, tier1Plan.audioBitrateKbps)

        // Tier 2: Severe BPP starvation (120s 1080p30 -> 10 MB) -> 2 notches + gentle VBV ceiling
        val media1080 = MediaInfo(
            uri = Uri.EMPTY,
            fileName = "long.mp4",
            baseName = "long",
            extension = "mp4",
            sizeBytes = 120_000_000L,
            durationSec = 120.0,
            videoCodec = "h264",
            width = 1920,
            height = 1080,
            pixFmt = "yuv420p",
            audioCodec = "aac",
            audioBitrateKbps = 128,
            fps = 30.0
        )
        val tier2Cfg = ModeConfigState(
            mode = ProcessingMode.CUSTOM_SIZE,
            targetMbInput = "10",
            codec = VideoCodecOption.H264,
            resolution = ResolutionOption.ORIGINAL,
            fps = FpsOption.ORIGINAL,
            audioBitrateInput = "128"
        )
        val tier2Plan = FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = 10.0,
            durationSec = 120.0,
            audioBitrateKbps = 128,
            codec = VideoCodecOption.H264,
            width = 1920,
            height = 1080,
            resolution = ResolutionOption.ORIGINAL,
            fpsOption = FpsOption.ORIGINAL,
            sourceFps = 30.0
        )
        assertEquals(2, tier2Plan.stress.tier)
        val tier2Steps = FfmpegMath.buildFfmpegSteps(
            media = media1080,
            config = tier2Cfg,
            inputTokenProvider = { "in.mp4" },
            outputPath = "/tmp/out.mp4",
            passLogPrefix = "/tmp/p"
        )
        assertTrue("Expected -maxrate VBV guard on Tier 2 H.264", tier2Steps[1].args.contains("-maxrate"))
        assertTrue("Expected -bufsize VBV guard on Tier 2 H.264", tier2Steps[1].args.contains("-bufsize"))

        // Normal encode must NOT emit -maxrate
        val normalSteps = FfmpegMath.buildFfmpegSteps(
            media = media1080.copy(durationSec = 60.0),
            config = tier2Cfg.copy(mode = ProcessingMode.DISCORD_20MB),
            inputTokenProvider = { "in.mp4" },
            outputPath = "/tmp/out.mp4",
            passLogPrefix = "/tmp/p"
        )
        assertFalse("Normal Tier 0 encode must not emit -maxrate", normalSteps[1].args.contains("-maxrate"))

        // Tier 3 extreme audio-choked starvation (300s 1080p -> 5 MB with 128k audio):
        // takes initiative to trim audio share so video + audio physically fits under 5 MB
        val tier3Plan = FfmpegMath.calculateAdaptiveTwoPassPlan(
            targetMb = 5.0,
            durationSec = 300.0,
            audioBitrateKbps = 128,
            codec = VideoCodecOption.H264,
            width = 1920,
            height = 1080,
            resolution = ResolutionOption.ORIGINAL,
            fpsOption = FpsOption.ORIGINAL,
            sourceFps = 30.0
        )
        assertEquals(3, tier3Plan.stress.tier)
        assertTrue("Expected extreme starvation to trim 128k audio share (got ${tier3Plan.audioBitrateKbps}k)", tier3Plan.audioBitrateKbps < 128)
        val totalEstimatedMb = ((tier3Plan.videoBitrateKbps + tier3Plan.audioBitrateKbps) * 300.0) / 8000.0
        assertTrue("Expected total estimated MB ($totalEstimatedMb) strictly below 5.0 MB", totalEstimatedMb < 5.0)
    }

    @Test
    fun sparseGopKeyframesAlwaysExposePreviousAndNextOptionsEvenBeyondTenSeconds() {
        // 15-second GOP video: keyframes at 0s, 15s, 30s, 45s
        val probe15s = """
            pts_time=0.000000|dts_time=0.000000|flags=K_
            pts_time=15.000000|dts_time=15.000000|flags=K_
            pts_time=30.000000|dts_time=30.000000|flags=K_
            pts_time=45.000000|dts_time=45.000000|flags=K_
        """.trimIndent()

        // Target 16s: next keyframe is 14s away (30s) -> MUST still be offered while prevKf (15s) auto-selects
        val startAt16 = FfmpegMath.parseKeyframePackets(probe15s, targetSec = 16.0, isStart = true)
        assertEquals(15.0, startAt16.prevKf!!, 0.001)
        assertEquals(30.0, startAt16.nextKf!!, 0.001)
        assertEquals(15.45, startAt16.recommendedRawSec!!, 0.001)
        assertTrue("Previous keyframe 0s must be selectable", startAt16.nearbyKeyframes.contains(0.0))
        assertTrue("Auto-selected prevKf 15s must be selectable", startAt16.nearbyKeyframes.contains(15.0))
        assertTrue("Next keyframe 30s (14s away) must be selectable", startAt16.nearbyKeyframes.contains(30.0))
        assertTrue("Subsequent keyframe 45s must be selectable", startAt16.nearbyKeyframes.contains(45.0))

        // Target landing directly on 15s keyframe: MUST still expose both 0s (prev) and 30s (next)
        val exactAt15 = FfmpegMath.parseKeyframePackets(probe15s, targetSec = 15.0, isStart = true)
        assertTrue(exactAt15.nearbyKeyframes.contains(0.0))
        assertTrue(exactAt15.nearbyKeyframes.contains(15.0))
        assertTrue(exactAt15.nearbyKeyframes.contains(30.0))

        // Ultra-sparse 25-second GOP video: target at 5s -> next keyframe is 20s away (25s)
        val probe25s = """
            pts_time=0.000000|dts_time=0.000000|flags=K_
            pts_time=25.000000|dts_time=25.000000|flags=K_
            pts_time=50.000000|dts_time=50.000000|flags=K_
        """.trimIndent()
        val endAt5 = FfmpegMath.parseKeyframePackets(probe25s, targetSec = 5.0, isStart = false)
        assertEquals(0.0, endAt5.prevKf!!, 0.001)
        assertEquals(25.0, endAt5.nextKf!!, 0.001)
        assertEquals(25.0, endAt5.recommendedRawSec!!, 0.001)
        assertTrue("25s keyframe (20s away) must be included in nearby options", endAt5.nearbyKeyframes.contains(25.0))
        assertTrue("50s keyframe must also be included in nearby options", endAt5.nearbyKeyframes.contains(50.0))
    }

    @Test
    fun videoOnlyCrfEnforcesAvSyncGuardsAndAdtsFilterForTransportStreams() {
        val mkvAac = MediaInfo(
            uri = Uri.EMPTY,
            fileName = "anime.mkv",
            baseName = "anime",
            extension = "mkv",
            sizeBytes = 90_000_000L,
            durationSec = 120.0,
            videoCodec = "h264",
            width = 1920,
            height = 1080,
            pixFmt = "yuv420p10le",
            audioCodec = "aac",
            audioBitrateKbps = 256
        )
        val mkvSteps = FfmpegMath.buildFfmpegSteps(
            media = mkvAac,
            config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF, codec = VideoCodecOption.H264),
            inputTokenProvider = { "in.mkv" },
            outputPath = "out.mp4",
            passLogPrefix = "pass"
        )
        val mkvArgs = mkvSteps.single().args
        assertTrue(mkvArgs.contains("-fps_mode"))
        assertTrue(mkvArgs.contains("passthrough"))
        assertTrue(mkvArgs.contains("-avoid_negative_ts"))
        assertTrue(mkvArgs.contains("make_zero"))
        assertTrue(mkvArgs.contains("-max_muxing_queue_size"))
        assertTrue(mkvArgs.contains("1024"))
        assertTrue(mkvArgs.contains("-c:a"))
        assertTrue(mkvArgs.contains("copy"))
        assertFalse("MKV AAC should not force aac_adtstoasc", mkvArgs.contains("aac_adtstoasc"))

        // Transport stream (.ts) with AAC requires aac_adtstoasc when copying into MP4
        val tsAac = mkvAac.copy(fileName = "broadcast.ts", baseName = "broadcast", extension = "ts")
        val tsArgs = FfmpegMath.buildFfmpegSteps(
            media = tsAac,
            config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF, codec = VideoCodecOption.H264),
            inputTokenProvider = { "in.ts" },
            outputPath = "out.mp4",
            passLogPrefix = "pass"
        ).single().args
        assertTrue("TS AAC must include aac_adtstoasc for MP4 copy", tsArgs.contains("aac_adtstoasc"))

        // VIDEO_AND_AUDIO_CRF includes aresample=async=1:first_pts=0 for zero A/V drift on pre-cut clips
        val vaArgs = FfmpegMath.buildFfmpegSteps(
            media = mkvAac,
            config = ModeConfigState(mode = ProcessingMode.VIDEO_AND_AUDIO_CRF, codec = VideoCodecOption.H264),
            inputTokenProvider = { "in.mkv" },
            outputPath = "out.mp4",
            passLogPrefix = "pass"
        ).single().args
        assertTrue(vaArgs.contains("-af"))
        assertTrue(vaArgs.contains("aresample=async=1:first_pts=0"))
    }

    @Test
    fun manualKeyframeTakesFinalAuthorityAndUsesMkvSafeSeekOffsetWithoutSkewingPreview() {
        val startScan = KeyframePointResult(
            targetSec = 20.0,
            prevKf = 16.0,
            nextKf = 22.0,
            recommendedRawSec = 16.45,
            recommendedDisplay = "00:16.0",
            nearbyKeyframes = listOf(16.0, 18.447, 22.0)
        )
        val endScan = KeyframePointResult(
            targetSec = 40.0,
            prevKf = 36.0,
            nextKf = 42.0,
            recommendedRawSec = 42.0,
            recommendedDisplay = "00:42.0",
            nearbyKeyframes = listOf(36.0, 39.2, 42.0)
        )
        // User manually overrides both start (18.447) and end (39.2) away from auto safe prev/next
        val cfg = ModeConfigState(
            mode = ProcessingMode.TRIM_VIDEO,
            startTimeInput = FfmpegMath.formatKeyframeTime(18.447),
            endTimeInput = FfmpegMath.formatKeyframeTime(39.2),
            trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
            trimTimeChoice = TrimTimeChoice.SNAP_KEYFRAMES,
            trimScan = TrimScanState(panelExpanded = true, startScan = startScan, endScan = endScan),
            selectedStartKfSec = 18.447,
            selectedEndKfSec = 39.2
        )

        // FFmpeg range applies MKV/B-frame-safe seek offset (+0.45s inside [18.447, 22.0)) on the
        // USER'S manual keyframe (never prevKf=16.0), while clipDur stays exact (39.2 - 18.447)!
        val (ffStart, ffEnd, ffDur) = FfmpegMath.resolveTrimRange(cfg, 120.0)
        assertEquals("18.897", ffStart)
        assertEquals("39.200", ffEnd)
        assertEquals(20.753, ffDur!!, 0.001)

        // Preview range exposes exact 18.447 (formats as 00:18.4, matching the chip 1:1!)
        val (prevStart, prevEnd, prevDur) = FfmpegMath.resolveTrimPreviewSeconds(cfg, 120.0)
        assertEquals(18.447, prevStart!!, 0.0001)
        assertEquals(39.2, prevEnd!!, 0.0001)
        assertEquals(20.753, prevDur!!, 0.001)
        assertEquals("00:18.4", FfmpegMath.formatKeyframeTime(prevStart))

        // Adaptive seek offset scales cleanly across 10s, 1s, 0.5s, and 0.0s GOPs
        assertEquals(0.0, FfmpegMath.computeSafeNoAccurateSeekSec(0.0, listOf(0.0, 5.0)), 0.0001)
        assertEquals(10.45, FfmpegMath.computeSafeNoAccurateSeekSec(10.0, listOf(5.0, 10.0, 20.0)), 0.0001)
        assertEquals(5.45, FfmpegMath.computeSafeNoAccurateSeekSec(5.0, listOf(4.0, 5.0, 6.0)), 0.0001)
        assertEquals(5.24, FfmpegMath.computeSafeNoAccurateSeekSec(5.0, listOf(4.5, 5.0, 5.5)), 0.0001)
    }

    @Test
    fun trimPreCutComputesMillimetricSyncOffsetsAndRecoversPriorKeyframeLandings() {
        val movieDur = 7200.0 // 2-hour 4K MKV movie
        val targetCfg = ModeConfigState(
            mode = ProcessingMode.TRIM_VIDEO,
            trimCutStrategy = TrimCutStrategy.PRECISE_REENCODE,
            trimTimeChoice = TrimTimeChoice.RAW_USER_TIMES,
            trimSubMode = TrimSubMode.CRF_AUDIO_COPY,
            startTimeInput = "1:10:00", // 4200.000s
            endTimeInput = "1:12:00"    // 4320.000s (120.000s requested clip)
        )
        assertTrue(FfmpegMath.shouldPreCutTrimForTurboPrep(targetCfg, movieDur))

        // Nearly full-length trim should NOT pre-cut unnecessarily
        val fullMovieCfg = targetCfg.copy(startTimeInput = "00:00.2", endTimeInput = "1:59:59")
        assertFalse(FfmpegMath.shouldPreCutTrimForTurboPrep(fullMovieCfg, movieDur))

        val startScan = KeyframePointResult(
            targetSec = 4200.0,
            prevKf = 4197.420,
            nextKf = 4201.500,
            recommendedRawSec = 4197.870,
            recommendedDisplay = "1:09:57.4",
            nearbyKeyframes = listOf(4193.0, 4197.420, 4201.500)
        )
        val endScan = KeyframePointResult(
            targetSec = 4320.0,
            prevKf = 4318.0,
            nextKf = 4323.180,
            recommendedRawSec = 4323.180,
            recommendedDisplay = "1:12:03.2",
            nearbyKeyframes = listOf(4318.0, 4323.180, 4327.0)
        )
        val bracket = FfmpegMath.resolveTrimPreCutBracket(
            origStartSec = 4200.0,
            origEndSec = 4320.0,
            mediaDurationSec = movieDur,
            startScan = startScan,
            endScan = endScan
        )!!
        assertEquals(4197.420, bracket.safeStartKfSec, 0.0001)
        assertEquals(4323.180, bracket.safeEndKfSec!!, 0.0001)
        assertEquals(125.760, bracket.expectedSpanSec!!, 0.001)
        assertEquals(4201.500, bracket.nextKfAfterStartSec!!, 0.0001)
        assertEquals(listOf(4193.0), bracket.priorStartCandidates)

        // Pre-cut args use MKV-safe -noaccurate_seek (+0.450s inside [4197.420, 4201.500)), -c copy, make_zero
        val mkvPreCutArgs = FfmpegMath.buildTrimPreCutArgs("movie.mkv", "precut.mkv", bracket, hasAudio = true)
        assertTrue(mkvPreCutArgs.contains("-noaccurate_seek"))
        assertEquals("4197.870", mkvPreCutArgs[mkvPreCutArgs.indexOf("-ss") + 1])
        assertEquals("4323.180", mkvPreCutArgs[mkvPreCutArgs.indexOf("-to") + 1])
        assertTrue(mkvPreCutArgs.contains("make_zero"))
        assertFalse("MKV intermediate must not force MP4 +faststart", mkvPreCutArgs.contains("+faststart"))

        val mp4PreCutArgs = FfmpegMath.buildTrimPreCutArgs("movie.mkv", "remux.mp4", bracket, hasAudio = true)
        assertTrue("MP4 remux pre-cut includes +faststart", mp4PreCutArgs.contains("+faststart"))

        // Case 1: Clean alignment (v0 = 0.000) -> exact [2.580, 122.580], duration = 120.000s
        val adjustedClean = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
            originalTargetConfig = targetCfg,
            bracket = bracket,
            preparedDurationSec = 125.760,
            preparedFirstVideoPtsSec = 0.0
        )!!
        assertEquals("2.580", adjustedClean.startTimeInput)
        assertEquals("122.580", adjustedClean.endTimeInput)

        // Case 2: Leading audio packet preceded video keyframe by 25ms (v0 = +0.025)
        // Adjusted clip shifts by exact +0.025 -> [2.605, 122.605], duration STILL exact 120.000s!
        val adjustedAudioLead = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
            originalTargetConfig = targetCfg,
            bracket = bracket,
            preparedDurationSec = 125.785,
            preparedFirstVideoPtsSec = 0.025
        )!!
        assertEquals("2.605", adjustedAudioLead.startTimeInput)
        assertEquals("122.605", adjustedAudioLead.endTimeInput)

        // Case 3: Exotic Open-GOP landed on prior keyframe (4193.0 -> span = 130.180s) -> auto-recovers
        // exact offset relative to 4193.0 ([7.000, 127.000]) instead of falling back to the full movie!
        val recoveredPrior = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
            originalTargetConfig = targetCfg,
            bracket = bracket,
            preparedDurationSec = 130.250,
            preparedFirstVideoPtsSec = 0.083
        )!!
        assertEquals("7.083", recoveredPrior.startTimeInput)
        assertEquals("127.083", recoveredPrior.endTimeInput)

        // Case 4: Truly corrupt demuxer seek index (duration off from every keyframe) -> returns null
        val corruptFallback = FfmpegMath.computeAdjustedTrimConfigAfterPreCut(
            originalTargetConfig = targetCfg,
            bracket = bracket,
            preparedDurationSec = 160.0,
            preparedFirstVideoPtsSec = 0.0
        )
        assertEquals(null, corruptFallback)

        // Case 5: Trim from 00:00 to 00:30 of a 2-hour movie -> no -ss seek needed, only -to!
        val frontOnlyCfg = targetCfg.copy(startTimeInput = "0", endTimeInput = "30")
        assertTrue(FfmpegMath.shouldPreCutTrimForTurboPrep(frontOnlyCfg, movieDur))
        val end30Scan = endScan.copy(targetSec = 30.0, nextKf = 32.5, nearbyKeyframes = listOf(28.0, 32.5, 36.0))
        val frontBracket = FfmpegMath.resolveTrimPreCutBracket(0.0, 30.0, movieDur, null, end30Scan)!!
        assertEquals(0.0, frontBracket.safeStartKfSec, 0.0001)
        assertEquals(32.5, frontBracket.safeEndKfSec!!, 0.0001)
        val frontArgs = FfmpegMath.buildTrimPreCutArgs("movie.mkv", "precut.mkv", frontBracket, hasAudio = true)
        assertFalse("Starting at 0.0 must omit -ss", frontArgs.contains("-ss"))
        assertTrue(frontArgs.contains("-to"))
    }

    @Test
    fun losslessTrimPreservesMkvForTheaterAudioAndTagsHevcMp4WithHvc1() {
        val dummyUri = android.net.Uri.EMPTY
        val mkvHevcDts = MediaInfo(
            uri = dummyUri,
            fileName = "movie_4k_hdr.mkv",
            baseName = "movie_4k_hdr",
            extension = "mkv",
            sizeBytes = 5_000_000_000L,
            durationSec = 3600.0,
            videoCodec = "hevc",
            width = 3840,
            height = 2160,
            pixFmt = "yuv420p10le",
            audioCodec = "dts",
            audioBitrateKbps = 754,
            audioChannels = 6
        )
        val mkvHevcAac = mkvHevcDts.copy(fileName = "clip_aac.mkv", baseName = "clip_aac", audioCodec = "aac", audioBitrateKbps = 256)
        val mp4HevcEac3 = mkvHevcDts.copy(fileName = "clip_eac3.mp4", baseName = "clip_eac3", extension = "mp4", audioCodec = "eac3")

        val fullLosslessCfg = ModeConfigState(
            mode = ProcessingMode.TRIM_VIDEO,
            trimCutStrategy = TrimCutStrategy.LOSSLESS_KEYFRAME,
            trimSubMode = TrimSubMode.FULL_LOSSLESS,
            startTimeInput = "00:01:00",
            endTimeInput = "00:02:00"
        )

        // 1) MKV + HEVC 10-bit + DTS -> stays .mkv losslessly (no MP4 faststart / no invalid mp4a DTS wrap)
        val mkvOutName = FfmpegMath.computeOutputFileName(mkvHevcDts, fullLosslessCfg)
        assertTrue("MKV with DTS must stay .mkv in FULL_LOSSLESS", mkvOutName.endsWith(".mkv"))
        val mkvSteps = FfmpegMath.buildFfmpegSteps(mkvHevcDts, fullLosslessCfg, { "\$IN" }, mkvOutName, "/tmp/p")
        val mkvArgs = mkvSteps.single().args
        assertTrue(mkvArgs.contains("-map") && mkvArgs.contains("0:V:0"))
        assertFalse("MKV output must not include MP4-only -movflags", mkvArgs.contains("-movflags"))
        assertFalse("MKV output must not inject MP4-only hvc1 tag", mkvArgs.contains("hvc1"))

        // 2) MKV + HEVC 10-bit + AAC -> .mp4 WITH -tag:v hvc1 (never hev1!)
        val mp4FromMkvAac = FfmpegMath.computeOutputFileName(mkvHevcAac, fullLosslessCfg)
        assertTrue(mp4FromMkvAac.endsWith(".mp4"))
        val mp4AacArgs = FfmpegMath.buildFfmpegSteps(mkvHevcAac, fullLosslessCfg, { "\$IN" }, mp4FromMkvAac, "/tmp/p").single().args
        assertTrue("HEVC MP4 lossless trim must include -tag:v hvc1", mp4AacArgs.contains("-tag:v") && mp4AacArgs.contains("hvc1"))
        assertTrue(mp4AacArgs.contains("-movflags") && mp4AacArgs.contains("+faststart"))

        // 3) MP4 + HEVC 10-bit + EAC3 -> .mp4 WITH -tag:v hvc1
        val mp4TrimName = FfmpegMath.computeOutputFileName(mp4HevcEac3, fullLosslessCfg)
        assertTrue(mp4TrimName.endsWith(".mp4"))
        val mp4TrimArgs = FfmpegMath.buildFfmpegSteps(mp4HevcEac3, fullLosslessCfg, { "\$IN" }, mp4TrimName, "/tmp/p").single().args
        assertTrue(mp4TrimArgs.contains("-tag:v") && mp4TrimArgs.contains("hvc1"))

        // 4) Trim Sub-mode 2 (VIDEO_LOSSLESS_AUDIO_AAC), REMUX_FORMAT, and AUDIO_ONLY_AAC on HEVC -> all get -tag:v hvc1
        val trimAacCfg = fullLosslessCfg.copy(trimSubMode = TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC, audioBitrateInput = "256")
        val trimAacArgs = FfmpegMath.buildFfmpegSteps(mkvHevcDts, trimAacCfg, { "\$IN" }, "out.mp4", "/tmp/p").single().args
        assertTrue(trimAacArgs.contains("-tag:v") && trimAacArgs.contains("hvc1"))

        val remuxArgs = FfmpegMath.buildFfmpegSteps(mkvHevcDts, ModeConfigState(mode = ProcessingMode.REMUX_FORMAT), { "\$IN" }, "out.mp4", "/tmp/p").single().args
        assertTrue(remuxArgs.contains("-tag:v") && remuxArgs.contains("hvc1"))

        val audioOnlyArgs = FfmpegMath.buildFfmpegSteps(mkvHevcDts, ModeConfigState(mode = ProcessingMode.AUDIO_ONLY_AAC, audioBitrateInput = "256"), { "\$IN" }, "out.mp4", "/tmp/p").single().args
        assertTrue(audioOnlyArgs.contains("-tag:v") && audioOnlyArgs.contains("hvc1"))
    }
}
