package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class DirectMediaCodecTranscoderTest {

    private fun countKeptFramesOverDuration(
        sourceFps: Double,
        durationSec: Double,
        targetFps: Double = 30.0
    ): Int {
        val dropper = TargetFpsFrameDropper(targetFps = targetFps)
        val totalFrames = (sourceFps * durationSec).roundToLong().toInt()
        val frameIntervalUs = 1_000_000.0 / sourceFps
        var kept = 0
        for (i in 0 until totalFrames) {
            val ptsUs = (i * frameIntervalUs).roundToLong()
            if (dropper.shouldKeepFrame(ptsUs)) {
                kept++
            }
        }
        return kept
    }

    @Test
    fun frameDropperHitsExact30FpsAcrossCommonCadences() {
        // 10-second clips across 60, 59.94, 50 PAL, 48, and 120 FPS should all yield ~300 frames (~30 FPS)
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 60.0, durationSec = 10.0))
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 59.94, durationSec = 10.0))
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 50.0, durationSec = 10.0))
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 48.0, durationSec = 10.0))
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 120.0, durationSec = 10.0))

        // Streams at or below 30 FPS should keep 100% of frames
        assertEquals(300, countKeptFramesOverDuration(sourceFps = 30.0, durationSec = 10.0))
        assertEquals(240, countKeptFramesOverDuration(sourceFps = 24.0, durationSec = 10.0))
    }

    @Test
    fun frameDropperReanchorsCleanlyAfterLongVfrPause() {
        val dropper = TargetFpsFrameDropper(targetFps = 30.0)
        assertTrue(dropper.shouldKeepFrame(0L))
        assertFalse(dropper.shouldKeepFrame(16_667L))
        // After a 500ms VFR screen-recording pause, the resumed frame is kept and accumulator re-anchors
        assertTrue(dropper.shouldKeepFrame(500_000L))
        assertFalse(dropper.shouldKeepFrame(516_667L))
        assertTrue(dropper.shouldKeepFrame(533_333L))
    }

    @Test
    fun hdrMetadataDetectionDistinguishesHdrFrom10BitSdr() {
        // Standard 8-bit BT.709 SDR
        assertFalse(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = 3, // COLOR_TRANSFER_SDR_VIDEO
                colorStandard = 1, // COLOR_STANDARD_BT709
                profile = 8,       // AVCProfileHigh
                mime = "video/avc"
            )
        )
        // 10-bit SDR HEVC (Main10 profile = 2 with SDR transfer & BT.709) must NOT be misclassified as HDR
        assertFalse(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = 3,
                colorStandard = 1,
                profile = 2, // HEVCProfileMain10
                mime = "video/hevc"
            )
        )
        // HDR10 (PQ / ST2084 = 6)
        assertTrue(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = 6,
                colorStandard = 1,
                profile = null,
                mime = "video/hevc"
            )
        )
        // HLG (transfer = 7)
        assertTrue(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = 7,
                colorStandard = null,
                profile = null,
                mime = "video/hevc"
            )
        )
        // BT.2020 color standard = 6
        assertTrue(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = null,
                colorStandard = 6,
                profile = null,
                mime = "video/hevc"
            )
        )
        // Explicit HDR10 / HDR10+ profiles even when container omits color transfer tags
        assertTrue(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = null,
                colorStandard = null,
                profile = 0x1000, // HEVCProfileMain10HDR10
                mime = "video/hevc"
            )
        )
        assertTrue(
            DirectTranscodeMath.isHdrColorMetadata(
                colorTransfer = null,
                colorStandard = null,
                profile = 0x2000, // AV1ProfileMain10HDR10Plus
                mime = "video/av01"
            )
        )
    }

    @Test
    fun untrimmedAvBaselinePreservesSyncAndGuardsCorruptOffsets() {
        // Normal aligned file
        assertEquals(0L, DirectTranscodeMath.resolveUntrimmedAvBaselineUs(0L, 0L))
        // 80ms initial video B-frame / edit-list delay with audio at 0ms -> baseline stays 0ms
        assertEquals(0L, DirectTranscodeMath.resolveUntrimmedAvBaselineUs(80_000L, 0L))
        // Lossless-cut clip starting at 12.5s -> strips leading offset while preserving exact 40ms A/V delta
        assertEquals(12_500_000L, DirectTranscodeMath.resolveUntrimmedAvBaselineUs(12_540_000L, 12_500_000L))
        // Missing or negative PTS -> fallback to independent per-track normalization
        assertNull(DirectTranscodeMath.resolveUntrimmedAvBaselineUs(null, 0L))
        assertNull(DirectTranscodeMath.resolveUntrimmedAvBaselineUs(0L, -1L))
        // Corrupt container with >2s divergence -> fallback to independent normalization
        assertNull(DirectTranscodeMath.resolveUntrimmedAvBaselineUs(0L, 5_000_000_000L))
    }

    @Test
    fun monotonicPtsSanitizerPreventsDuplicatesAndBackwardJumps() {
        // First frame clamps negative values cleanly to 0
        assertEquals(0L, DirectTranscodeMath.sanitizeMonotonicPtsUs(-5_000L, Long.MIN_VALUE, 16_667L))
        assertEquals(33_333L, DirectTranscodeMath.sanitizeMonotonicPtsUs(33_333L, 0L, 16_667L))
        // Duplicate timestamp advances by minStepUs
        assertEquals(50_000L, DirectTranscodeMath.sanitizeMonotonicPtsUs(33_333L, 33_333L, 16_667L))
        // Backward jump from lossless-cut edit-list advances monotonically
        assertEquals(51_000L, DirectTranscodeMath.sanitizeMonotonicPtsUs(12_000L, 50_000L, 1_000L))
    }
}
