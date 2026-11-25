package com.example.domain

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HybridHardwareDecodeTest {

    private fun sampleMedia(
        codec: String = "hevc",
        width: Int = 1920,
        height: Int = 1080,
        pixFmt: String = "yuv420p",
        durationSec: Double = 45.0,
        colorTransfer: String? = null
    ) = MediaInfo(
        uri = Uri.EMPTY,
        fileName = "clip.mp4",
        baseName = "clip",
        extension = "mp4",
        sizeBytes = 40_000_000L,
        durationSec = durationSec,
        videoCodec = codec,
        width = width,
        height = height,
        pixFmt = pixFmt,
        audioCodec = "aac",
        fps = 30.0,
        colorTransfer = colorTransfer
    )

    private val fullFfmpegDecoders = setOf("h264_mediacodec", "hevc_mediacodec", "vp9_mediacodec", "av1_mediacodec")
    private val fullDeviceHwMimes = setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")

    @Test
    fun parsesFfmpegMediaCodecVideoDecodersOnly() {
        val raw = """
            Decoders:
             V..... = Video
             A..... = Audio
             ------
             V....D h264                 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10
             V..... h264_mediacodec      H.264 Android MediaCodec decoder (codec h264)
             V..... hevc_mediacodec      H.265 Android MediaCodec decoder (codec hevc)
             V..... vp9_mediacodec       VP9 Android MediaCodec decoder (codec vp9)
             A..... aac_mediacodec       Fake audio entry should be ignored
        """.trimIndent()

        val parsed = FfmpegMath.parseAvailableMediaCodecDecoders(raw)
        assertEquals(setOf("h264_mediacodec", "hevc_mediacodec", "vp9_mediacodec"), parsed)
    }

    @Test
    fun enablesHybridHwDecodeFor8Bit720pTo4kReEncodeModes() {
        val hevc4k = sampleMedia(codec = "hevc", width = 3840, height = 2160, pixFmt = "yuv420p")
        val h264Portrait = sampleMedia(codec = "h264", width = 1080, height = 1920, pixFmt = "yuvj420p")
        val av1Fhd = sampleMedia(codec = "av1", width = 1920, height = 1080, pixFmt = "yuv420p")

        assertEquals(
            "hevc_mediacodec",
            FfmpegMath.resolveHybridHardwareDecoder(
                media = hevc4k,
                config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        assertEquals(
            "h264_mediacodec",
            FfmpegMath.resolveHybridHardwareDecoder(
                media = h264Portrait,
                config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        assertEquals(
            "av1_mediacodec",
            FfmpegMath.resolveHybridHardwareDecoder(
                media = av1Fhd,
                config = ModeConfigState(mode = ProcessingMode.TRIM_VIDEO, trimSubMode = TrimSubMode.CRF_AUDIO_AAC),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
    }

    @Test
    fun rejects10BitNon420LowResTinyClipsAndCopyModes() {
        // 10-bit HDR HEVC must stay on exact CPU software decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "hevc", pixFmt = "yuv420p10le"),
                config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        // 4:2:2 pixel format must stay on CPU software decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "h264", pixFmt = "yuv422p"),
                config = ModeConfigState(mode = ProcessingMode.VIDEO_AND_AUDIO_CRF),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        // Low-resolution 480p stays on CPU decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "h264", width = 854, height = 480),
                config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        // Ultra-short clip (<3s) stays on CPU decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "h264", durationSec = 2.0),
                config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        // Lossless copy / remux / audio modes never engage HW decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "hevc"),
                config = ModeConfigState(mode = ProcessingMode.TRIM_VIDEO, trimSubMode = TrimSubMode.FULL_LOSSLESS),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "hevc"),
                config = ModeConfigState(mode = ProcessingMode.REMUX_FORMAT),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = fullDeviceHwMimes
            )
        )
        // Missing device hardware decoder for AV1 skips HW decode
        assertNull(
            FfmpegMath.resolveHybridHardwareDecoder(
                media = sampleMedia(codec = "av1"),
                config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB),
                ffmpegMediaCodecDecoders = fullFfmpegDecoders,
                deviceHardwareDecoderMimes = setOf("video/avc", "video/hevc")
            )
        )
    }

    @Test
    fun injectsHardwareDecoderBeforeInputWithoutTouchingOutputEncoderArgs() {
        val twoPassArgs = listOf(
            "-y", "-threads", "0", "-i", "saf:123",
            "-r", "30", "-filter_threads", "0", "-vf", "scale=-2:720",
            "-sn", "-dn", "-c:v", "libx264", "-threads", "0", "-preset", "fast",
            "-b:v", "1500k", "-pix_fmt", "yuv420p", "-an", "-f", "null", "/dev/null"
        )
        val injected = FfmpegMath.injectHardwareDecoderArgs(twoPassArgs, "hevc_mediacodec")
        assertEquals(
            listOf(
                "-y", "-c:v", "hevc_mediacodec", "-i", "saf:123",
                "-r", "30", "-filter_threads", "0", "-vf", "scale=-2:720",
                "-sn", "-dn", "-c:v", "libx264", "-threads", "0", "-preset", "fast",
                "-b:v", "1500k", "-pix_fmt", "yuv420p", "-an", "-f", "null", "/dev/null"
            ),
            injected
        )

        val trimCrfArgs = listOf(
            "-y", "-ss", "10.000", "-to", "25.000", "-threads", "0", "-i", "saf:999",
            "-sn", "-dn", "-c:v", "libx265", "-threads", "0", "-preset", "fast", "-crf", "20"
        )
        val injectedTrim = FfmpegMath.injectHardwareDecoderArgs(trimCrfArgs, "h264_mediacodec")
        assertEquals(
            listOf(
                "-y", "-ss", "10.000", "-to", "25.000", "-c:v", "h264_mediacodec", "-i", "saf:999",
                "-sn", "-dn", "-c:v", "libx265", "-threads", "0", "-preset", "fast", "-crf", "20"
            ),
            injectedTrim
        )
    }
}
