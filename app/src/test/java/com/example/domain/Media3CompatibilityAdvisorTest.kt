package com.example.domain

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Media3CompatibilityAdvisorTest {

    private fun sampleDevice(
        extraDecoders: Set<String> = emptySet(),
        probed: Boolean = true
    ) = DeviceCodecSnapshot(
        decoderMimes = setOf(
            "video/avc",
            "video/hevc",
            "video/x-vnd.on2.vp9",
            "video/av01",
            "audio/mp4a-latm",
            "audio/mpeg",
            "audio/opus",
            "audio/vorbis",
            "audio/flac"
        ) + extraDecoders,
        encoderMimes = setOf("video/avc", "video/hevc", "audio/mp4a-latm"),
        supportsHevc10BitEncode = true,
        supportsHevcHdrEditing = true,
        sdkInt = 36,
        probed = probed
    )

    private fun media(
        ext: String = "mp4",
        vCodec: String? = "hevc",
        pixFmt: String? = "yuv420p10le",
        vProfile: String? = if (pixFmt?.contains("10") == true) "Main 10" else "High",
        colorTransfer: String? = null,
        aCodec: String? = "aac",
        aBitrate: Int? = 256,
        aChannels: Int? = 2
    ) = MediaInfo(
        uri = Uri.EMPTY,
        fileName = "clip.$ext",
        baseName = "clip",
        extension = ext,
        sizeBytes = 120_000_000L,
        durationSec = 60.0,
        videoCodec = vCodec,
        width = 3840,
        height = 2160,
        pixFmt = pixFmt,
        audioCodec = aCodec,
        fps = 60.0,
        audioBitrateKbps = aBitrate,
        audioChannels = aChannels,
        videoProfile = vProfile,
        colorTransfer = colorTransfer
    )

    @Test
    fun compatible4kHevc10BitAacMp4PassesDirectly() {
        val clip = media(ext = "mp4", vCodec = "hevc", pixFmt = "yuv420p10le", colorTransfer = "smpte2084", aCodec = "aac")
        assertTrue(clip.isHdrVideo)
        assertEquals("10bit", clip.bitDepthIn)

        val report = Media3CompatibilityAdvisor.diagnose(
            media = clip,
            config = ModeConfigState(mode = ProcessingMode.TRIM_VIDEO, trimSubMode = TrimSubMode.CRF_AUDIO_AAC),
            device = sampleDevice()
        )
        assertEquals(Media3IssueKind.COMPATIBLE, report.issueKind)
        assertFalse(report.requiresImmediateRecovery)
    }

    @Test
    fun pinpointsAudioIncompatibilitiesDirectlyAndHonorsDeviceAc3Decoders() {
        val dtsMkv = media(ext = "mkv", vCodec = "h264", pixFmt = "yuv420p", aCodec = "dts", aChannels = 6)
        val dtsReport = Media3CompatibilityAdvisor.diagnose(dtsMkv, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice())
        assertEquals(Media3IssueKind.AUDIO_INCOMPATIBLE, dtsReport.issueKind)
        assertTrue(dtsReport.requiresImmediateRecovery)
        assertEquals(384, dtsReport.recommendedAudioBitrateKbps)
        assertEquals(
            TurboRecoveryStep.OFFER_AUDIO_CONVERT,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = dtsReport,
                hasAudio = true,
                errorCodeName = null,
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )

        // 5.1 FLAC & 24-bit PCM are flagged as audio-incompatible
        val flac51 = media(ext = "mkv", vCodec = "h264", pixFmt = "yuv420p", aCodec = "flac", aChannels = 6)
        assertEquals(
            Media3IssueKind.AUDIO_INCOMPATIBLE,
            Media3CompatibilityAdvisor.diagnose(flac51, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice()).issueKind
        )
        val pcm24 = media(ext = "mov", vCodec = "hevc", pixFmt = "yuv420p", aCodec = "pcm_s24le", aChannels = 2)
        assertEquals(
            Media3IssueKind.AUDIO_INCOMPATIBLE,
            Media3CompatibilityAdvisor.diagnose(pcm24, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice()).issueKind
        )

        // AC-3 depends on actual device MediaCodec decoder inventory
        val ac3Clip = media(ext = "mkv", vCodec = "h264", pixFmt = "yuv420p", aCodec = "ac3", aChannels = 6)
        assertEquals(
            Media3IssueKind.AUDIO_INCOMPATIBLE,
            Media3CompatibilityAdvisor.diagnose(ac3Clip, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice()).issueKind
        )
        assertEquals(
            Media3IssueKind.COMPATIBLE,
            Media3CompatibilityAdvisor.diagnose(
                ac3Clip,
                ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                sampleDevice(extraDecoders = setOf("audio/ac3"))
            ).issueKind
        )
    }

    @Test
    fun pinpointsVideoIncompatibilitiesIncludingHi10PAndNon420Chroma() {
        // 10-bit H.264 (Hi10P) + healthy AAC in MKV -> Video-Only (keeps AAC 100% lossless!)
        val hi10p = media(ext = "mkv", vCodec = "h264", pixFmt = "yuv420p10le", vProfile = "High 10", aCodec = "aac")
        val hi10Report = Media3CompatibilityAdvisor.diagnose(hi10p, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice())
        assertEquals(Media3IssueKind.VIDEO_INCOMPATIBLE, hi10Report.issueKind)
        assertTrue(hi10Report.canUseVideoOnlyPrep)
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_ONLY,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = hi10Report,
                hasAudio = true,
                errorCodeName = null,
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false,
                hasTriedVideoOnlyConvert = false
            )
        )
        // If Video-Only prep was tried and Turbo still failed, escalate cleanly to Video+Audio
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = hi10Report,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_AUDIO_PROCESSING_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false,
                hasTriedVideoOnlyConvert = true
            )
        )

        // ProRes + MP4-incompatible audio (PCM 16-bit) -> cannot copy into MP4, routes to Video+Audio
        val proresPcm = media(ext = "mov", vCodec = "prores", pixFmt = "yuv422p10le", aCodec = "pcm_s16le")
        val proresPcmReport = Media3CompatibilityAdvisor.diagnose(proresPcm, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice())
        assertEquals(Media3IssueKind.VIDEO_INCOMPATIBLE, proresPcmReport.issueKind)
        assertFalse(proresPcmReport.canUseVideoOnlyPrep)
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = proresPcmReport,
                hasAudio = true,
                errorCodeName = null,
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )

        // Legacy sync-risk container (AVI with MPEG-2 + MP3) -> routes to Video+Audio to avoid A/V drift
        val legacyAvi = media(ext = "avi", vCodec = "mpeg2video", pixFmt = "yuv420p", aCodec = "mp3")
        val legacyAviReport = Media3CompatibilityAdvisor.diagnose(legacyAvi, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice())
        assertEquals(Media3IssueKind.VIDEO_INCOMPATIBLE, legacyAviReport.issueKind)
        assertFalse(legacyAviReport.canUseVideoOnlyPrep)
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = legacyAviReport,
                hasAudio = true,
                errorCodeName = null,
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )

        // Both video (MPEG-2) + audio (DTS) incompatible -> Video+Audio
        val mpeg2Dts = media(ext = "mkv", vCodec = "mpeg2video", pixFmt = "yuv420p", aCodec = "dts")
        assertEquals(
            Media3IssueKind.VIDEO_AND_AUDIO_INCOMPATIBLE,
            Media3CompatibilityAdvisor.diagnose(mpeg2Dts, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice()).issueKind
        )

        // Device missing AV1 decoder + healthy Opus audio in MKV -> Video-Only eligible
        val av1Clip = media(ext = "mkv", vCodec = "av1", pixFmt = "yuv420p", aCodec = "opus")
        val noAv1Device = sampleDevice().copy(decoderMimes = setOf("video/avc", "video/hevc", "audio/opus"))
        val av1Report = Media3CompatibilityAdvisor.diagnose(av1Clip, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), noAv1Device)
        assertEquals(Media3IssueKind.VIDEO_INCOMPATIBLE, av1Report.issueKind)
        assertTrue(av1Report.canUseVideoOnlyPrep)
    }

    @Test
    fun pinpointsContainerOnlyIssuesForUnsupportedContainersAndFlvTrim() {
        val vobClip = media(ext = "vob", vCodec = "h264", pixFmt = "yuv420p", aCodec = "aac")
        val vobReport = Media3CompatibilityAdvisor.diagnose(vobClip, ModeConfigState(mode = ProcessingMode.DISCORD_20MB), sampleDevice())
        assertEquals(Media3IssueKind.CONTAINER_ONLY, vobReport.issueKind)
        assertEquals(
            TurboRecoveryStep.OFFER_REMUX,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = vobReport,
                hasAudio = true,
                errorCodeName = null,
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )

        val flvClip = media(ext = "flv", vCodec = "h264", pixFmt = "yuv420p", aCodec = "aac")
        val flvTrimReport = Media3CompatibilityAdvisor.diagnose(
            flvClip,
            ModeConfigState(mode = ProcessingMode.TRIM_VIDEO, trimSubMode = TrimSubMode.CRF_AUDIO_AAC),
            sampleDevice()
        )
        assertEquals(Media3IssueKind.CONTAINER_ONLY, flvTrimReport.issueKind)
    }

    @Test
    fun progressiveRecoveryLadderEscalatesCleanlyAcrossAllSteps() {
        val compatible = Media3CompatibilityAdvisor.diagnose(
            media(ext = "mkv", vCodec = "h264", pixFmt = "yuv420p", aCodec = "aac"),
            ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
            sampleDevice()
        )
        assertEquals(Media3IssueKind.COMPATIBLE, compatible.issueKind)

        // Step 1: Generic format failure -> Offer Remux
        assertEquals(
            TurboRecoveryStep.OFFER_REMUX,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_MUXING_FAILED",
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )
        // Step 2: Remux failed / still fails after Remux (with audio) -> Offer Audio-Only
        assertEquals(
            TurboRecoveryStep.OFFER_AUDIO_CONVERT,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "REMUX_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )
        // Step 2 (no audio): Skips Audio-Only straight to Video-Only (and exhausts once tried)
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_ONLY,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = false,
                errorCodeName = "REMUX_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false,
                hasTriedVideoOnlyConvert = false
            )
        )
        assertEquals(
            TurboRecoveryStep.EXHAUSTED,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = false,
                errorCodeName = "ERROR_CODE_ENCODING_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false,
                hasTriedVideoOnlyConvert = true
            )
        )
        // Step 3: Audio-Only tried & still fails -> Offer Video+Audio
        assertEquals(
            TurboRecoveryStep.OFFER_VIDEO_AND_AUDIO,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = true,
                hasTriedVideoAudioConvert = false
            )
        )
        // Step 4: Video+Audio already tried & still fails -> Exhausted
        assertEquals(
            TurboRecoveryStep.EXHAUSTED,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_ENCODING_FAILED",
                errorDetail = null,
                hasTriedRemux = true,
                hasTriedAudioConvert = true,
                hasTriedVideoAudioConvert = true
            )
        )
        // Shortcut: Runtime audio-specific failure skips Remux straight to Audio-Only
        assertEquals(
            TurboRecoveryStep.OFFER_AUDIO_CONVERT,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_AUDIO_PROCESSING_FAILED",
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )
        // 10-bit HW encoder refusal does not loop through Remux/Audio-Only
        assertEquals(
            TurboRecoveryStep.EXHAUSTED,
            Media3CompatibilityAdvisor.resolveNextRecoveryStep(
                report = compatible,
                hasAudio = true,
                errorCodeName = "ERROR_CODE_10BIT_NOT_PRESERVED",
                errorDetail = null,
                hasTriedRemux = false,
                hasTriedAudioConvert = false,
                hasTriedVideoAudioConvert = false
            )
        )
    }

    @Test
    fun highQualityAudioRecommendationMatchesSourceWithoutDegradation() {
        // Stereo lossless FLAC -> 320k
        assertEquals(320, Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media(aCodec = "flac", aChannels = 2)))
        // 5.1 surround lossless TrueHD -> 384k
        assertEquals(384, Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media(aCodec = "truehd", aChannels = 6)))
        // Lossy 192k stereo -> 192k
        assertEquals(192, Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media(aCodec = "ac3", aBitrate = 192, aChannels = 2)))
        // Lossy 384k 5.1 -> 384k
        assertEquals(384, Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media(aCodec = "ac3", aBitrate = 384, aChannels = 6)))
        // Low 96k lossy -> floor 128k high quality
        assertEquals(128, Media3CompatibilityAdvisor.recommendHighQualityAudioBitrateKbps(media(aCodec = "mp2", aBitrate = 96, aChannels = 2)))
        // Recovery choices always contain the recommended default and stay sorted
        val choices51 = Media3CompatibilityAdvisor.audioRecoveryBitrateChoices(media(aCodec = "dts", aChannels = 6))
        assertTrue(choices51.contains(384))
        assertEquals(choices51.sorted(), choices51)
    }
}
