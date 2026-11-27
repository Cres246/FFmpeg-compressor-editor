package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for one-shot [ActivePerformanceStamps] classification, SIMD token
 * extraction, truthfulness across workloads/builds, and single-line terminal formatting.
 */
class ActivePerformanceStampsTest {

    private val fullArm64Cpu = CpuSimdProfile(
        hasNeon = true,
        hasDotProd = true,
        hasI8mm = true,
        hasSve = false,
        hasSve2 = false
    )

    private val fullEncoderReport = ActiveEncoderSimdReport(
        x264Caps = "ARMv8 NEON DotProd I8MM",
        x265Caps = "NEON Neon_DotProd Neon_I8MM"
    )

    @Test
    fun `extractSimdTokens normalizes x264 and x265 capability strings cleanly`() {
        assertEquals(
            listOf("NEON", "DotProd", "I8MM"),
            ActivePerformanceStamps.extractSimdTokens("ARMv8 NEON DotProd I8MM")
        )
        assertEquals(
            listOf("NEON", "DotProd", "I8MM"),
            ActivePerformanceStamps.extractSimdTokens("NEON Neon_DotProd Neon_I8MM")
        )
        assertEquals(
            listOf("NEON", "DotProd", "SVE2"),
            ActivePerformanceStamps.extractSimdTokens("NEON Neon_DotProd SVE2")
        )
        assertEquals(
            emptyList<String>(),
            ActivePerformanceStamps.extractSimdTokens(null)
        )
    }

    @Test
    fun `x264 custom ARM64 build with SAF and Scudo emits exact active stamps`() {
        val stamps = ActivePerformanceStamps.resolve(
            workload = ActivePerformanceStamps.SoftwareWorkloadKind.VIDEO_X264,
            customSimdBuild = true,
            cpuSimd = fullArm64Cpu,
            encoderSimd = fullEncoderReport,
            scudoLazyDecayActive = true,
            usesSafRead = true
        )

        assertEquals(
            listOf("NEON", "DotProd", "I8MM", "-O3", "LTO-O3", "Zero-PLT", "Scudo", "256K SAF"),
            stamps
        )
        assertEquals(
            "[Aktif] NEON · DotProd · I8MM · -O3 · LTO-O3 · Zero-PLT · Scudo · 256K SAF",
            ActivePerformanceStamps.formatTerminalLine("[Aktif]", stamps)
        )
    }

    @Test
    fun `x265 encode on device without I8MM omits I8MM truthfully`() {
        val noI8mmReport = ActiveEncoderSimdReport(
            x264Caps = "ARMv8 NEON DotProd",
            x265Caps = "NEON Neon_DotProd"
        )
        val stamps = ActivePerformanceStamps.resolve(
            workload = ActivePerformanceStamps.SoftwareWorkloadKind.VIDEO_X265,
            customSimdBuild = true,
            cpuSimd = fullArm64Cpu.copy(hasI8mm = false),
            encoderSimd = noI8mmReport,
            scudoLazyDecayActive = true,
            usesSafRead = false
        )

        assertEquals(
            listOf("NEON", "DotProd", "-O3", "LTO-O3", "Zero-PLT", "Scudo"),
            stamps
        )
        assertFalse("I8MM" in stamps)
        assertFalse("256K SAF" in stamps)
    }

    @Test
    fun `lossless stream copy omits video SIMD tokens while keeping build and I O boosts`() {
        val stamps = ActivePerformanceStamps.resolve(
            workload = ActivePerformanceStamps.SoftwareWorkloadKind.STREAM_COPY,
            customSimdBuild = true,
            cpuSimd = fullArm64Cpu,
            encoderSimd = fullEncoderReport,
            scudoLazyDecayActive = true,
            usesSafRead = true
        )

        assertEquals(
            listOf("-O3", "LTO-O3", "Zero-PLT", "Scudo", "256K SAF"),
            stamps
        )
        assertFalse("NEON" in stamps)
        assertFalse("DotProd" in stamps)
        assertFalse("I8MM" in stamps)
    }

    @Test
    fun `audio-only transcode includes NEON but omits video DotProd and I8MM`() {
        val stamps = ActivePerformanceStamps.resolve(
            workload = ActivePerformanceStamps.SoftwareWorkloadKind.AUDIO_TRANSCODE,
            customSimdBuild = true,
            cpuSimd = fullArm64Cpu,
            encoderSimd = fullEncoderReport,
            scudoLazyDecayActive = false,
            usesSafRead = true
        )

        assertEquals(
            listOf("NEON", "-O3", "LTO-O3", "Zero-PLT", "256K SAF"),
            stamps
        )
        assertFalse("DotProd" in stamps)
        assertFalse("I8MM" in stamps)
        assertFalse("Scudo" in stamps)
    }

    @Test
    fun `non-custom fallback build never claims O3 LTO Zero-PLT or 256K SAF`() {
        val stamps = ActivePerformanceStamps.resolve(
            workload = ActivePerformanceStamps.SoftwareWorkloadKind.STREAM_COPY,
            customSimdBuild = false,
            cpuSimd = fullArm64Cpu,
            encoderSimd = null,
            scudoLazyDecayActive = false,
            usesSafRead = true
        )

        assertTrue(stamps.isEmpty())
        assertNull(ActivePerformanceStamps.formatTerminalLine("[Aktif]", stamps))
    }

    private fun customStep(
        tool: CustomCommandTool,
        args: List<String>
    ) = CustomCommandStep(
        args = args,
        outputFileNames = emptyList(),
        isNullOutputOnly = false,
        expectedDurationSec = null,
        tool = tool
    )

    @Test
    fun `classifyCustomCommandWorkload detects x265 x264 copy audio and ffprobe accurately`() {
        assertEquals(
            ActivePerformanceStamps.SoftwareWorkloadKind.PROBE_ONLY,
            ActivePerformanceStamps.classifyCustomCommandWorkload(
                listOf(customStep(CustomCommandTool.FFPROBE, listOf("-show_streams", "in.mp4")))
            )
        )
        assertEquals(
            ActivePerformanceStamps.SoftwareWorkloadKind.STREAM_COPY,
            ActivePerformanceStamps.classifyCustomCommandWorkload(
                listOf(customStep(CustomCommandTool.FFMPEG, listOf("-i", "in.mkv", "-c", "copy", "out.mp4")))
            )
        )
        assertEquals(
            ActivePerformanceStamps.SoftwareWorkloadKind.VIDEO_X265,
            ActivePerformanceStamps.classifyCustomCommandWorkload(
                listOf(customStep(CustomCommandTool.FFMPEG, listOf("-i", "in.mp4", "-c:v", "libx265", "out.mp4")))
            )
        )
        assertEquals(
            ActivePerformanceStamps.SoftwareWorkloadKind.VIDEO_X264,
            ActivePerformanceStamps.classifyCustomCommandWorkload(
                listOf(customStep(CustomCommandTool.FFMPEG, listOf("-i", "in.mp4", "-vcodec", "libx264", "out.mp4")))
            )
        )
        assertEquals(
            ActivePerformanceStamps.SoftwareWorkloadKind.AUDIO_TRANSCODE,
            ActivePerformanceStamps.classifyCustomCommandWorkload(
                listOf(customStep(CustomCommandTool.FFMPEG, listOf("-i", "in.mp4", "-c:v", "copy", "-c:a", "aac", "out.mp4")))
            )
        )
    }
}
