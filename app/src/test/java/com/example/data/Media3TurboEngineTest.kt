package com.example.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.domain.EnglishStrings
import com.example.domain.FpsOption
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.ResolutionOption
import com.example.domain.TurkishStrings
import com.example.domain.VideoCodecOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Media3TurboEngineTest {

    private fun sampleMedia(
        width: Int = 1920,
        height: Int = 1080,
        durationSec: Double = 60.0,
        audioCodec: String? = "aac",
        fps: Double? = 60.0
    ) = MediaInfo(
        uri = Uri.EMPTY,
        fileName = "sample.mp4",
        baseName = "sample",
        extension = "mp4",
        sizeBytes = 80_000_000L,
        durationSec = durationSec,
        videoCodec = "h264",
        width = width,
        height = height,
        pixFmt = "yuv420p",
        audioCodec = audioCodec,
        fps = fps
    )

    private fun defaultVideoCaps(supportsCbr: Boolean = true) = HardwareVideoEncoderCaps(
        codecName = "c2.qti.avc.encoder",
        minBitrateBps = 100_000,
        maxBitrateBps = 50_000_000,
        supportsCbr = supportsCbr,
        supportsVbr = true,
        supportsAvcHighLevel52 = true,
        supportsHevc10Bit = true,
        videoCapabilities = null
    )

    private fun defaultAudioCaps() = HardwareAudioEncoderCaps(
        minBitrateBps = 32_000,
        maxBitrateBps = 510_000,
        maxChannelCount = 6,
        minSampleRateHz = 8_000,
        maxSampleRateHz = 48_000
    )

    @Test
    fun targetSizeBitratePlanEnforcesStrictDecimalMbAndAacAntiLeak() {
        val media = sampleMedia(durationSec = 60.0)
        val config = ModeConfigState(
            mode = ProcessingMode.DISCORD_20MB,
            codec = VideoCodecOption.H264,
            audioBitrateInput = "128"
        )
        // Source AAC is 320 kbps while user requested 128k -> must force AAC re-encode to prevent size leak
        val highAudioProbe = SourceTrackProbe(
            displayDims = 1920 to 1080,
            durationSec = 60.0,
            sourceTotalBitrateBps = 10_000_000,
            sourceAudioBitrateBps = 320_000,
            sourceAudioMime = "audio/mp4a-latm",
            sourceAudioChannels = 2,
            sourceAudioSampleRate = 48_000,
            sourceVideoFps = 60.0
        )

        val plan = Media3TurboEngine.computeTurboBitratePlan(
            media = media,
            config = config,
            probe = highAudioProbe,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        assertTrue(plan.isTargetSizeMode)
        assertTrue(plan.useCbr)
        assertTrue(plan.forceAudioReencode)
        assertEquals(128_000, plan.audioBitrateBps)
        val projectedBytes = ((plan.videoBitrateBps + plan.audioBitrateBps).toDouble() / 8.0) * 60.0
        assertTrue("Expected projected bytes ($projectedBytes) strictly below 20,000,000", projectedBytes < 20_000_000.0)

        // When source AAC is already <= requested + 12k, pass-through cleanly without re-encoding audio
        val matchingAudioProbe = highAudioProbe.copy(sourceAudioBitrateBps = 128_000)
        val passThroughPlan = Media3TurboEngine.computeTurboBitratePlan(
            media = media,
            config = config,
            probe = matchingAudioProbe,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        assertFalse(passThroughPlan.forceAudioReencode)
        assertEquals(128_000, passThroughPlan.audioBitrateBps)
    }

    @Test
    fun channelAwareAacClampPreventsStereoInitFailures() {
        val aacCaps = defaultAudioCaps()
        // Stereo 512k request must clamp to 320k (160k per channel)
        assertEquals(320_000, aacCaps.clampBitrateForChannels(512_000, channelCount = 2))
        // Mono 256k request clamps to 160k
        assertEquals(160_000, aacCaps.clampBitrateForChannels(256_000, channelCount = 1))
        // 5.1 surround 448k request is preserved below 510k hardware ceiling
        assertEquals(448_000, aacCaps.clampBitrateForChannels(448_000, channelCount = 6))
        // Very low request clamps up to minBitrateBps
        assertEquals(32_000, aacCaps.clampBitrateForChannels(8_000, channelCount = 2))
    }

    @Test
    fun turboResolveTargetDimensionsMatchesThreeWayAspectProtection() {
        val baseCfg = ModeConfigState(mode = ProcessingMode.GMAIL_25MB, fps = FpsOption.ORIGINAL)

        // Portrait 9:16 -> 720p => 720x1280
        assertEquals(
            720 to 1280,
            Media3TurboEngine.resolveTargetDimensions(
                sampleMedia(width = 1080, height = 1920),
                baseCfg.copy(resolution = ResolutionOption.P720)
            )
        )
        // Cinema ultrawide 3840x1600 -> 1080p => 1920x800 (long edge capped to 1920)
        assertEquals(
            1920 to 800,
            Media3TurboEngine.resolveTargetDimensions(
                sampleMedia(width = 3840, height = 1600),
                baseCfg.copy(resolution = ResolutionOption.P1080)
            )
        )
        // Standard 16:9 1920x1080 -> 720p => 1280x720
        assertEquals(
            1280 to 720,
            Media3TurboEngine.resolveTargetDimensions(
                sampleMedia(width = 1920, height = 1080),
                baseCfg.copy(resolution = ResolutionOption.P720)
            )
        )
        // Odd dimensions with Original -> even dimensions
        assertEquals(
            1078 to 1918,
            Media3TurboEngine.resolveTargetDimensions(
                sampleMedia(width = 1079, height = 1919),
                baseCfg.copy(resolution = ResolutionOption.ORIGINAL)
            )
        )
    }

    @Test
    fun fileProviderConfigDeclaresAllPublicOutputSubfolders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val declaredPaths = mutableSetOf<String>()
        val parser = context.resources.getXml(R.xml.file_paths)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "external-path") {
                parser.getAttributeValue(null, "path")?.let { declaredPaths.add(it) }
            }
            event = parser.next()
        }
        parser.close()

        assertTrue(declaredPaths.contains("Movies/FFmpeg/"))
        assertTrue(declaredPaths.contains("Music/FFmpeg/"))
        assertTrue(declaredPaths.contains("Pictures/FFmpeg/"))
        assertTrue(declaredPaths.contains("Download/FFmpeg/"))
    }

    @Test
    fun openSourceCreditsIncludeMedia3InBothLanguages() {
        assertTrue(TurkishStrings.creditsMedia3SectionLabel.isNotBlank())
        assertTrue(TurkishStrings.creditsMedia3Author.isNotBlank())
        assertTrue(EnglishStrings.creditsMedia3SectionLabel.isNotBlank())
        assertTrue(EnglishStrings.creditsMedia3Author.isNotBlank())
    }

    @Test
    fun turboPreventsUpscalingUses8MbCustomDefaultAndShowsAudioCopySpecs() {
        // 720p source with 1080p selected must NOT upscale to 1920x1080
        assertEquals(
            1280 to 720,
            Media3TurboEngine.resolveTargetDimensions(
                sampleMedia(width = 1280, height = 720),
                ModeConfigState(mode = ProcessingMode.DISCORD_20MB, resolution = ResolutionOption.P1080)
            )
        )
        // Blank CUSTOM_SIZE target defaults to 8.0 MB matching UI default
        assertEquals(
            8.0,
            Media3TurboEngine.resolveTargetMb(ModeConfigState(mode = ProcessingMode.CUSTOM_SIZE, targetMbInput = ""))!!,
            0.001
        )
        // VIDEO_ONLY_CRF Turbo specs strip must show Ses copy / Audio copy rather than AAC
        val trSpecs = com.example.service.EncodingForegroundService.buildTurboUnifiedSpecs(
            media = sampleMedia(),
            config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF),
            s = TurkishStrings
        )
        val enSpecs = com.example.service.EncodingForegroundService.buildTurboUnifiedSpecs(
            media = sampleMedia(),
            config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF),
            s = EnglishStrings
        )
        assertTrue("Expected 'Ses copy' in '$trSpecs'", trSpecs.contains("Ses copy"))
        assertTrue("Expected 'Audio copy' in '$enSpecs'", enSpecs.contains("Audio copy"))
    }

    @Test
    fun cancelConfirmationAndAudioWarningStringsExistInBothLanguages() {
        listOf(TurkishStrings, EnglishStrings).forEach { s ->
            assertTrue(s.cancelConfirmTitle.isNotBlank())
            assertTrue(s.cancelConfirmBody.isNotBlank())
            assertTrue(s.cancelConfirmYes.isNotBlank())
            assertTrue(s.cancelConfirmKeepRunning.isNotBlank())
            assertTrue(s.audioBitrateSourceCapSub(128, 128).contains("128"))
            assertTrue(s.audioBitrateUpscaleWarning(128, "AAC", 256).contains("128"))
            assertTrue(s.errUnsupportedMediaFile.isNotBlank())
            assertTrue(s.errEncodingInProgress.isNotBlank())
        }
    }

    @Test
    fun turboAppliesSmallTargetNotchOnlyForOneAndTwoMb() {
        val media = sampleMedia(durationSec = 30.0)
        val probe = SourceTrackProbe(
            displayDims = 1280 to 720,
            durationSec = 30.0,
            sourceTotalBitrateBps = 4_000_000,
            sourceAudioBitrateBps = 128_000,
            sourceAudioMime = "audio/mp4a-latm",
            sourceAudioChannels = 2,
            sourceAudioSampleRate = 48_000,
            sourceVideoFps = 30.0
        )
        val plan1Mb = Media3TurboEngine.computeTurboBitratePlan(
            media = media,
            config = ModeConfigState(mode = ProcessingMode.CUSTOM_SIZE, targetMbInput = "1", audioBitrateInput = "64"),
            probe = probe,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        val projected1MbBytes = ((plan1Mb.videoBitrateBps + plan1Mb.audioBitrateBps).toDouble() / 8.0) * 30.0
        assertTrue("Expected 1 MB projected bytes ($projected1MbBytes) safely below 940 KB", projected1MbBytes < 940_000.0)
    }

    @Test
    fun turboStressTiersKeepNormalUnchangedAndProtectHighResStarvation() {
        val baseProbe = SourceTrackProbe(
            displayDims = 1920 to 1080,
            durationSec = 60.0,
            sourceTotalBitrateBps = 12_000_000,
            sourceAudioBitrateBps = 128_000,
            sourceAudioMime = "audio/mp4a-latm",
            sourceAudioChannels = 2,
            sourceAudioSampleRate = 48_000,
            sourceVideoFps = 30.0
        )

        // Normal 60s 1080p30 -> 20 MB stays Tier 0
        val normalPlan = Media3TurboEngine.computeTurboBitratePlan(
            media = sampleMedia(durationSec = 60.0, fps = 30.0),
            config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB, audioBitrateInput = "128"),
            probe = baseProbe,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        assertEquals(0, normalPlan.stressTier)

        // 90s -> 10 MB: 720p30 (heeded warning) stays Tier 0; 1080p30 (ignored warning) gets Tier 1 tighter bitrate
        val probe90s = baseProbe.copy(durationSec = 90.0)
        val plan720 = Media3TurboEngine.computeTurboBitratePlan(
            media = sampleMedia(durationSec = 90.0, fps = 30.0),
            config = ModeConfigState(
                mode = ProcessingMode.CUSTOM_SIZE,
                targetMbInput = "10",
                resolution = ResolutionOption.P720,
                fps = FpsOption.FPS_30,
                audioBitrateInput = "128"
            ),
            probe = probe90s,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        val plan1080Ignored = Media3TurboEngine.computeTurboBitratePlan(
            media = sampleMedia(durationSec = 90.0, fps = 30.0),
            config = ModeConfigState(
                mode = ProcessingMode.CUSTOM_SIZE,
                targetMbInput = "10",
                resolution = ResolutionOption.ORIGINAL,
                fps = FpsOption.ORIGINAL,
                audioBitrateInput = "128"
            ),
            probe = probe90s,
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        assertEquals(0, plan720.stressTier)
        assertEquals(1, plan1080Ignored.stressTier)
        assertTrue(
            "Ignored-warning 1080p bitrate (${plan1080Ignored.videoBitrateBps}) must be tighter than 720p (${plan720.videoBitrateBps})",
            plan1080Ignored.videoBitrateBps < plan720.videoBitrateBps
        )

        // 120s 1080p30 -> 10 MB hits Tier 2
        val planTier2 = Media3TurboEngine.computeTurboBitratePlan(
            media = sampleMedia(durationSec = 120.0, fps = 30.0),
            config = ModeConfigState(
                mode = ProcessingMode.CUSTOM_SIZE,
                targetMbInput = "10",
                resolution = ResolutionOption.ORIGINAL,
                fps = FpsOption.ORIGINAL,
                audioBitrateInput = "128"
            ),
            probe = baseProbe.copy(durationSec = 120.0),
            hwCaps = defaultVideoCaps(supportsCbr = true),
            aacCaps = defaultAudioCaps()
        )
        assertEquals(2, planTier2.stressTier)

        // Extreme audio-choked 300s 1080p30 -> 5 MB hits Tier 3, trims audio share, forces audio re-encode, stays < 5 MB
        val planTier3 = Media3TurboEngine.computeTurboBitratePlan(
            media = sampleMedia(durationSec = 300.0, fps = 30.0),
            config = ModeConfigState(
                mode = ProcessingMode.CUSTOM_SIZE,
                targetMbInput = "5",
                resolution = ResolutionOption.ORIGINAL,
                fps = FpsOption.ORIGINAL,
                audioBitrateInput = "128"
            ),
            probe = baseProbe.copy(durationSec = 300.0),
            hwCaps = defaultVideoCaps(supportsCbr = true).copy(minBitrateBps = 64_000),
            aacCaps = defaultAudioCaps()
        )
        assertEquals(3, planTier3.stressTier)
        assertTrue("Expected Tier 3 audio cap below 128k (got ${planTier3.audioBitrateBps})", planTier3.audioBitrateBps < 128_000)
        assertTrue("Expected forceAudioReencode when Tier 3 caps AAC below source", planTier3.forceAudioReencode)
        val projectedBytes = ((planTier3.videoBitrateBps + planTier3.audioBitrateBps).toDouble() / 8.0) * 300.0
        assertTrue("Expected Tier 3 projected bytes ($projectedBytes) strictly below 5,000,000", projectedBytes < 5_000_000.0)
    }

    @Test
    fun turboTenBitAndProgressiveRecoveryStringsExistInBothLanguages() {
        val hevcMain10NoPixFmt = sampleMedia().copy(
            videoCodec = "hevc",
            pixFmt = null,
            videoProfile = "Main 10",
            colorTransfer = "arib-std-b67"
        )
        assertEquals("10bit", hevcMain10NoPixFmt.bitDepthIn)
        assertTrue(hevcMain10NoPixFmt.isHdrVideo)

        listOf(TurkishStrings, EnglishStrings).forEach { s ->
            assertTrue(s.turboStatusRestoringAudio.isNotBlank())
            assertTrue(s.turboTrimPreCutStatus.isNotBlank())
            assertTrue(s.turbo10BitNotPreservedTitle.isNotBlank())
            assertTrue(s.turbo10BitNotPreservedBody.isNotBlank())
            assertTrue(s.turbo10BitNotPreservedHint.isNotBlank())
            assertTrue(s.turboAudioLostBody.isNotBlank())
            assertTrue(s.turboDiagAudioIncompatible("DTS").contains("DTS"))
            assertTrue(s.turboDiagVideoIncompatible("PRORES").contains("PRORES"))
            assertTrue(s.turboDiagVideoAndAudioIncompatible("MPEG2", "DTS").contains("DTS"))
            assertTrue(s.turboDiagContainerOnly("VOB").contains("VOB"))
            assertTrue(s.turboAudioConvertPromptTitle.isNotBlank())
            assertTrue(s.turboAudioConvertPromptBody(true).isNotBlank())
            assertTrue(s.turboAudioConvertPromptBody(false).isNotBlank())
            assertTrue(s.turboAudioConvertSourceHint(256, false, 256).contains("256"))
            assertTrue(s.turboVideoOnlyPromptTitle.isNotBlank())
            assertTrue(s.turboVideoOnlyPromptBody(true).isNotBlank())
            assertTrue(s.turboVideoOnlyPromptBody(false).isNotBlank())
            assertTrue(s.turboVideoOnlyPromptYes.isNotBlank())
            assertTrue(s.turboTryVideoOnlyButton.isNotBlank())
            assertTrue(s.turboVideoOnlyPrepModeTitle.isNotBlank())
            assertTrue(s.turboDiagVideoOnlyIncompatible("H.264 10bit", "AAC").contains("AAC"))
            assertTrue(s.turboVideoAudioPromptTitle.isNotBlank())
            assertTrue(s.turboVideoAudioPromptBody(true).isNotBlank())
            assertTrue(s.turboVideoAudioPrepBannerBody("Discord").contains("Discord"))
            assertTrue(s.turboAfterVideoAudioFailedTitle.isNotBlank())
            assertTrue(s.turboAfterVideoAudioFailedBody.isNotBlank())
        }
    }
}
