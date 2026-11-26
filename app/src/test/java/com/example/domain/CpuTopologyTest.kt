package com.example.domain

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CpuTopologyTest {

    private fun sampleMedia() = MediaInfo(
        uri = Uri.EMPTY,
        fileName = "clip.mp4",
        baseName = "clip",
        extension = "mp4",
        sizeBytes = 60_000_000L,
        durationSec = 60.0,
        videoCodec = "hevc",
        width = 1920,
        height = 1080,
        pixFmt = "yuv420p10le",
        audioCodec = "aac",
        fps = 30.0
    )

    @Test
    fun snapdragon8Gen3_1plus5plus2_detectsClustersAndKeepsAllCoresUnlocked() {
        // 2 Little A520 + 5 Big A720 + 1 Prime X4
        val cores = listOf(
            CpuCoreSpec(0, 2_265_600L, 320),
            CpuCoreSpec(1, 2_265_600L, 320),
            CpuCoreSpec(2, 2_956_800L, 760),
            CpuCoreSpec(3, 2_956_800L, 760),
            CpuCoreSpec(4, 3_148_800L, 880),
            CpuCoreSpec(5, 3_148_800L, 880),
            CpuCoreSpec(6, 3_148_800L, 880),
            CpuCoreSpec(7, 3_302_400L, 1024)
        )
        val topo = CpuTopology.classify(cores)
        assertTrue(topo.isHeterogeneous)
        assertFalse(topo.allBigCore)
        assertFalse(topo.shouldPinEncoderPoolToPerformanceCores)
        assertFalse(topo.shouldPinLeadThreadOnly)
        assertArrayEquals(intArrayOf(2, 3, 4, 5, 6, 7), topo.performanceCpuIds)
        assertArrayEquals(intArrayOf(0, 1), topo.littleCpuIds)

        val profile = topo.computeEncoderThreadProfile()
        assertEquals(0, profile.x264EncoderThreads)
        assertEquals("log-level=warning:pools=+", profile.buildX265BaseParams())

        val h265Steps = FfmpegMath.buildFfmpegSteps(
            media = sampleMedia(),
            config = ModeConfigState(mode = ProcessingMode.DISCORD_20MB, codec = VideoCodecOption.H265),
            inputTokenProvider = { "in.mp4" },
            outputPath = "/tmp/out.mp4",
            passLogPrefix = "/tmp/pass",
            strings = TurkishStrings,
            cpuTopology = topo
        )
        assertTrue(h265Steps[0].args.contains("log-level=warning:pools=+:pass=1:stats=/tmp/pass.log"))

        val h264Steps = FfmpegMath.buildFfmpegSteps(
            media = sampleMedia(),
            config = ModeConfigState(mode = ProcessingMode.VIDEO_ONLY_CRF, codec = VideoCodecOption.H264),
            inputTokenProvider = { "in.mp4" },
            outputPath = "/tmp/out.mp4",
            passLogPrefix = "/tmp/pass",
            strings = TurkishStrings,
            cpuTopology = topo
        )
        val encIdx = h264Steps[0].args.indexOf("libx264")
        assertEquals("-threads", h264Steps[0].args[encIdx + 1])
        assertEquals("0", h264Steps[0].args[encIdx + 2])
    }

    @Test
    fun classic1plus3plus4_freqOnlyFallback_detectsClustersAndKeepsAll8CoresUnlocked() {
        // No cpu_capacity (older kernel / restricted SELinux), only cpuinfo_max_freq available
        val cores = listOf(
            CpuCoreSpec(0, 1_804_800L, null),
            CpuCoreSpec(1, 1_804_800L, null),
            CpuCoreSpec(2, 1_804_800L, null),
            CpuCoreSpec(3, 1_804_800L, null),
            CpuCoreSpec(4, 2_745_600L, null),
            CpuCoreSpec(5, 2_745_600L, null),
            CpuCoreSpec(6, 2_745_600L, null),
            CpuCoreSpec(7, 3_187_200L, null)
        )
        val topo = CpuTopology.classify(cores)
        assertFalse(topo.shouldPinEncoderPoolToPerformanceCores)
        assertFalse(topo.shouldPinLeadThreadOnly)
        assertArrayEquals(intArrayOf(4, 5, 6, 7), topo.performanceCpuIds)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), topo.littleCpuIds)

        val profile = topo.computeEncoderThreadProfile()
        assertEquals(0, profile.x264EncoderThreads)
        assertEquals("log-level=warning:pools=+", profile.buildX265BaseParams())
    }

    @Test
    fun snapdragon8Elite_2plus6AllBig_keepsAll8CoresUnlocked() {
        // 6 Oryon Performance (3.53GHz) + 2 Oryon Prime (4.32GHz) — ZERO Little cores!
        val cores = listOf(
            CpuCoreSpec(0, 3_532_800L, 780),
            CpuCoreSpec(1, 3_532_800L, 780),
            CpuCoreSpec(2, 3_532_800L, 780),
            CpuCoreSpec(3, 3_532_800L, 780),
            CpuCoreSpec(4, 3_532_800L, 780),
            CpuCoreSpec(5, 3_532_800L, 780),
            CpuCoreSpec(6, 4_320_000L, 1024),
            CpuCoreSpec(7, 4_320_000L, 1024)
        )
        val topo = CpuTopology.classify(cores)
        assertTrue(topo.allBigCore)
        assertFalse(topo.shouldPinEncoderPoolToPerformanceCores)
        assertFalse(topo.shouldPinLeadThreadOnly)
        assertEquals(8, topo.performanceCpuIds.size)

        // Frequency-only fallback on Snapdragon 8 Elite must also keep all 8 cores unlocked
        val topoFreqOnly = CpuTopology.classify(cores.map { it.copy(capacity = null) })
        assertTrue(topoFreqOnly.allBigCore)
        assertFalse(topoFreqOnly.shouldPinEncoderPoolToPerformanceCores)

        val profile = topo.computeEncoderThreadProfile()
        assertEquals(0, profile.x264EncoderThreads)
        assertEquals("log-level=warning:pools=+", profile.buildX265BaseParams())
    }

    @Test
    fun midrange2Big6Little_keepsAllCoresUnlocked() {
        val cores = listOf(
            CpuCoreSpec(0, 1_800_000L, null),
            CpuCoreSpec(1, 1_800_000L, null),
            CpuCoreSpec(2, 1_800_000L, null),
            CpuCoreSpec(3, 1_800_000L, null),
            CpuCoreSpec(4, 1_800_000L, null),
            CpuCoreSpec(5, 1_800_000L, null),
            CpuCoreSpec(6, 2_400_000L, null),
            CpuCoreSpec(7, 2_400_000L, null)
        )
        val topo = CpuTopology.classify(cores)
        assertFalse(topo.shouldPinEncoderPoolToPerformanceCores)
        assertFalse(topo.shouldPinLeadThreadOnly)
        assertArrayEquals(intArrayOf(6, 7), topo.performanceCpuIds)

        val profile = topo.computeEncoderThreadProfile()
        assertEquals(0, profile.x264EncoderThreads)
        assertEquals("log-level=warning:pools=+", profile.buildX265BaseParams())
    }

    @Test
    fun symmetricOrRestrictedSysfs_fallsBackCleanlyToAllCoreAuto() {
        val restricted = (0..7).map { CpuCoreSpec(it, null, null) }
        val topo = CpuTopology.classify(restricted)
        assertFalse(topo.isHeterogeneous)
        assertFalse(topo.shouldPinEncoderPoolToPerformanceCores)
        assertFalse(topo.shouldPinLeadThreadOnly)
        assertEquals("log-level=warning:pools=+", topo.computeEncoderThreadProfile().buildX265BaseParams())
    }

    @Test
    fun parsesLinuxCpuRangeStrings() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), CpuTopology.parseCpuListString("0-7\n"))
        assertEquals(listOf(0, 1, 4, 5, 6), CpuTopology.parseCpuListString("0-1,4-6"))
        assertEquals(listOf(0, 2, 3, 7), CpuTopology.parseCpuListString("0,2-3,7"))
    }

    @Test
    fun cpuSimdProfile_decodesOldArmv8AndModernSnapdragonBitmasks() {
        val oldArmv8 = CpuSimdProfile.fromBitmask(CpuSimdProfile.BIT_NEON)
        assertTrue(oldArmv8.hasNeon)
        assertFalse(oldArmv8.hasDotProd)
        assertFalse(oldArmv8.hasI8mm)
        assertEquals("NEON", oldArmv8.summaryLabel())

        val modernSnapdragon = CpuSimdProfile.fromBitmask(
            CpuSimdProfile.BIT_NEON or CpuSimdProfile.BIT_DOTPROD or CpuSimdProfile.BIT_I8MM
        )
        assertTrue(modernSnapdragon.hasNeon)
        assertTrue(modernSnapdragon.hasDotProd)
        assertTrue(modernSnapdragon.hasI8mm)
        assertFalse(modernSnapdragon.hasSve2)
        assertEquals("NEON · DotProd · I8MM", modernSnapdragon.summaryLabel())

        val flagshipSve2 = CpuSimdProfile.fromBitmask(
            CpuSimdProfile.BIT_NEON or CpuSimdProfile.BIT_DOTPROD or CpuSimdProfile.BIT_I8MM or
                CpuSimdProfile.BIT_SVE or CpuSimdProfile.BIT_SVE2
        )
        assertEquals("NEON · DotProd · I8MM · SVE2", flagshipSve2.summaryLabel())
    }

    @Test
    fun cpuSimdProfile_parsesProcCpuInfoFallbackCleanly() {
        val snapdragonCpuInfo = """
            processor : 0
            BogoMIPS  : 38.40
            Features  : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 flagm2 frint svei8mm i8mm bf16
        """.trimIndent()
        val parsed = CpuSimdProfile.parseCpuInfoFeatures(snapdragonCpuInfo)
        assertTrue(parsed.hasNeon)
        assertTrue(parsed.hasDotProd)
        assertTrue(parsed.hasI8mm)
        assertTrue(parsed.hasSve)
        assertTrue(parsed.hasSve2)
        assertEquals("NEON · DotProd · I8MM · SVE2", parsed.summaryLabel())

        val legacyArmv8CpuInfo = "Features : fp asimd evtstrm aes pmull sha1 sha2 crc32"
        val legacy = CpuSimdProfile.parseCpuInfoFeatures(legacyArmv8CpuInfo)
        assertTrue(legacy.hasNeon)
        assertFalse(legacy.hasDotProd)
        assertFalse(legacy.hasI8mm)
        assertEquals("NEON", legacy.summaryLabel())
    }

    @Test
    fun activeEncoderSimdReport_parsesX264AndX265CapabilityLogs() {
        val x264Log = """
            [libx264 @ 0xb400007] using cpu capabilities: ARMv8 NEON DotProd I8MM
            [libx264 @ 0xb400007] profile High, level 1.0
        """.trimIndent()
        val x265Log = """
            x265 [info]: HEVC encoder version 4.1
            x265 [info]: using cpu capabilities: NEON Neon_DotProd
        """.trimIndent()

        val x264Caps = ActiveEncoderSimdReport.parseEncoderCapabilities(x264Log)
        val x265Caps = ActiveEncoderSimdReport.parseEncoderCapabilities(x265Log)

        assertEquals("ARMv8 NEON DotProd I8MM", x264Caps)
        assertEquals("NEON Neon_DotProd", x265Caps)
        assertEquals(
            "x264: ARMv8 NEON DotProd I8MM · x265: NEON Neon_DotProd",
            ActiveEncoderSimdReport(x264Caps, x265Caps).formatCompactLine()
        )
        assertEquals(null, ActiveEncoderSimdReport.parseEncoderCapabilities("no caps line here"))
    }
}

