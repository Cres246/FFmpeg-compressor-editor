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
}
