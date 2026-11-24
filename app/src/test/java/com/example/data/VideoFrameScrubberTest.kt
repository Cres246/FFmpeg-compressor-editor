package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameScrubberTest {

    @Test
    fun `clampPreviewSec guards bounds and invalid values`() {
        assertEquals(0.0, TrimPreviewMath.clampPreviewSec(-5.0, 30.0), 0.0001)
        assertEquals(0.0, TrimPreviewMath.clampPreviewSec(Double.NaN, 30.0), 0.0001)
        assertEquals(0.0, TrimPreviewMath.clampPreviewSec(Double.POSITIVE_INFINITY, 30.0), 0.0001)
        assertEquals(29.75, TrimPreviewMath.clampPreviewSec(30.0, 30.0), 0.0001)
        assertEquals(29.75, TrimPreviewMath.clampPreviewSec(99.0, 30.0), 0.0001)
        assertEquals(12.4, TrimPreviewMath.clampPreviewSec(12.4, 30.0), 0.0001)
        assertEquals(0.0, TrimPreviewMath.clampPreviewSec(0.15, 0.2), 0.0001)
        assertEquals(42.5, TrimPreviewMath.clampPreviewSec(42.5, null), 0.0001)
    }

    @Test
    fun `scrubBucketMs scales with duration and clamps to 100ms-1000ms`() {
        assertEquals(250L, TrimPreviewMath.scrubBucketMs(null))
        assertEquals(100L, TrimPreviewMath.scrubBucketMs(6.0))
        assertEquals(500L, TrimPreviewMath.scrubBucketMs(60.0))
        assertEquals(1_000L, TrimPreviewMath.scrubBucketMs(7200.0))
    }

    @Test
    fun `scrubToleranceMs scales with duration and clamps to 1200ms-12000ms`() {
        assertEquals(3_000L, TrimPreviewMath.scrubToleranceMs(null))
        assertEquals(1_200L, TrimPreviewMath.scrubToleranceMs(10.0))
        assertEquals(3_333L, TrimPreviewMath.scrubToleranceMs(60.0))
        assertEquals(12_000L, TrimPreviewMath.scrubToleranceMs(3600.0))
    }

    @Test
    fun `quantizeBucketMs snaps to nearest bucket boundary`() {
        assertEquals(0L, TrimPreviewMath.quantizeBucketMs(0.0, 500L))
        assertEquals(500L, TrimPreviewMath.quantizeBucketMs(0.26, 500L))
        assertEquals(0L, TrimPreviewMath.quantizeBucketMs(0.24, 500L))
        assertEquals(12_350L, TrimPreviewMath.quantizeBucketMs(12.341, 50L))
        assertEquals(12_400L, TrimPreviewMath.quantizeBucketMs(12.389, 50L))
    }

    @Test
    fun `computePrewarmTimestamps generates strictly interior ascending points`() {
        assertTrue(TrimPreviewMath.computePrewarmTimestamps(null).isEmpty())
        assertTrue(TrimPreviewMath.computePrewarmTimestamps(0.8).isEmpty())

        val samples60s = TrimPreviewMath.computePrewarmTimestamps(60.0, maxSamples = 24)
        assertEquals(24, samples60s.size)
        assertTrue(samples60s.first() > 0.0)
        assertTrue(samples60s.last() <= 59.75)
        for (i in 1 until samples60s.size) {
            assertTrue(samples60s[i] > samples60s[i - 1])
        }

        val samplesShort = TrimPreviewMath.computePrewarmTimestamps(3.0, maxSamples = 24)
        assertEquals(6, samplesShort.size)
    }

    @Test
    fun `findNearestCachedKey selects closest key within tolerance`() {
        val keys = listOf(2_000L, 5_000L, 10_000L, 15_000L)
        assertEquals(5_000L, TrimPreviewMath.findNearestCachedKey(5_000L, keys, 2_000L))
        assertEquals(5_000L, TrimPreviewMath.findNearestCachedKey(6_400L, keys, 2_000L))
        assertEquals(10_000L, TrimPreviewMath.findNearestCachedKey(8_100L, keys, 2_000L))
        assertNull(TrimPreviewMath.findNearestCachedKey(20_000L, keys, 2_000L))
        assertNull(TrimPreviewMath.findNearestCachedKey(5_000L, emptyList(), 2_000L))
    }

    @Test
    fun `buildFfmpegThumbArgs uses fast keyframe seek and 256px fast_bilinear for FAST_SCRUB`() {
        val args = TrimPreviewMath.buildFfmpegThumbArgs(
            safPath = "saf:123",
            sec = 12.345,
            outputPath = "/tmp/thumb.jpg",
            fastScrub = true
        ).toList()

        val noAccIdx = args.indexOf("-noaccurate_seek")
        val ssIdx = args.indexOf("-ss")
        val inputIdx = args.indexOf("-i")
        assertTrue("Expected -noaccurate_seek before -ss", noAccIdx in 0 until ssIdx)
        assertTrue("Expected -ss before -i", ssIdx in 0 until inputIdx)
        assertEquals("12.345", args[ssIdx + 1])
        assertEquals("scale='min(256,iw)':-2:flags=fast_bilinear", args[args.indexOf("-vf") + 1])
        assertEquals("8", args[args.indexOf("-q:v") + 1])
        assertEquals("2", args[args.indexOf("-threads") + 1])
        assertTrue(args.contains("-an") && args.contains("-sn") && args.contains("-dn"))
    }

    @Test
    fun `buildFfmpegThumbArgs uses accurate seek and 480px quality 4 for EXACT_CRISP`() {
        val args = TrimPreviewMath.buildFfmpegThumbArgs(
            safPath = "saf:456",
            sec = 7.5,
            outputPath = "/tmp/crisp.jpg",
            fastScrub = false
        ).toList()

        assertFalse("EXACT_CRISP must not use -noaccurate_seek", args.contains("-noaccurate_seek"))
        assertEquals("7.500", args[args.indexOf("-ss") + 1])
        assertEquals("scale='min(480,iw)':-2:flags=fast_bilinear", args[args.indexOf("-vf") + 1])
        assertEquals("4", args[args.indexOf("-q:v") + 1])
        assertEquals("2", args[args.indexOf("-threads") + 1])
    }
}
