package com.example.data

import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInputStagerTest {

    private val gb = 1_000_000_000L
    private val mb = 1_000_000L

    @Test
    fun twoPassModeStagesWhenAbundantStorageAvailable() {
        val decision = LocalInputCachePolicy.decide(
            uriScheme = "content",
            mode = ProcessingMode.DISCORD_20MB,
            sourceBytes = 350 * mb,
            availableStorageBytes = 12 * gb,
            totalStorageBytes = 128 * gb,
            targetMb = 20.0
        )
        assertEquals(LocalInputCachePolicy.StagingDecision.STAGE_TO_CACHE, decision)
    }

    @Test
    fun fallsBackSilentlyWhenStorageWouldBreachSafetyReserve() {
        // Total 128 GB -> reserve clamped at 3 GB. Source 500 MB + 24 MB output + 3 GB = ~3.524 GB required.
        val decision = LocalInputCachePolicy.decide(
            uriScheme = "content",
            mode = ProcessingMode.DISCORD_20MB,
            sourceBytes = 500 * mb,
            availableStorageBytes = 3_200 * mb,
            totalStorageBytes = 128 * gb,
            targetMb = 20.0
        )
        assertEquals(LocalInputCachePolicy.StagingDecision.SKIP_FALLBACK_SAF, decision)
    }

    @Test
    fun safetyReserveClampsBetweenOnePointFiveAndThreeGb() {
        assertEquals(
            LocalInputCachePolicy.MIN_SAFETY_RESERVE_BYTES,
            LocalInputCachePolicy.computeSafetyReserveBytes(8 * gb)
        )
        assertEquals(
            2_000_000_000L,
            LocalInputCachePolicy.computeSafetyReserveBytes(20 * gb)
        )
        assertEquals(
            LocalInputCachePolicy.MAX_SAFETY_RESERVE_BYTES,
            LocalInputCachePolicy.computeSafetyReserveBytes(256 * gb)
        )
        assertEquals(
            LocalInputCachePolicy.DEFAULT_SAFETY_RESERVE_BYTES,
            LocalInputCachePolicy.computeSafetyReserveBytes(0L)
        )
    }

    @Test
    fun directFileUriSkipsDuplicateCopy() {
        val decision = LocalInputCachePolicy.decide(
            uriScheme = "file",
            mode = ProcessingMode.DISCORD_20MB,
            sourceBytes = 400 * mb,
            availableStorageBytes = 50 * gb,
            totalStorageBytes = 256 * gb
        )
        assertEquals(LocalInputCachePolicy.StagingDecision.ALREADY_DIRECT_FILE, decision)
    }

    @Test
    fun unknownSourceSizeSkipsStagingSafely() {
        val decision = LocalInputCachePolicy.decide(
            uriScheme = "content",
            mode = ProcessingMode.VIDEO_ONLY_CRF,
            sourceBytes = 0L,
            availableStorageBytes = 50 * gb,
            totalStorageBytes = 256 * gb
        )
        assertEquals(LocalInputCachePolicy.StagingDecision.SKIP_FALLBACK_SAF, decision)
    }

    @Test
    fun streamCopyAndAudioOnlyModesSkipStagingToAvoidExtraIo() {
        listOf(
            ProcessingMode.REMUX_FORMAT,
            ProcessingMode.EXTRACT_AUDIO,
            ProcessingMode.AUDIO_ONLY_AAC
        ).forEach { mode ->
            assertFalse(
                "Mode $mode should not stage",
                LocalInputCachePolicy.isModeEligibleForStaging(mode = mode, sourceBytes = 200 * mb)
            )
        }
    }

    @Test
    fun trimLosslessSkipsStagingWhileCrfTrimStagesSmartly() {
        assertFalse(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.TRIM_VIDEO,
                trimSubMode = TrimSubMode.FULL_LOSSLESS,
                sourceBytes = 300 * mb
            )
        )
        assertFalse(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.TRIM_VIDEO,
                trimSubMode = TrimSubMode.VIDEO_LOSSLESS_AUDIO_AAC,
                sourceBytes = 300 * mb
            )
        )
        // Medium file CRF trim -> eligible
        assertTrue(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.TRIM_VIDEO,
                trimSubMode = TrimSubMode.CRF_AUDIO_AAC,
                sourceBytes = 600 * mb,
                trimCoverageRatio = 0.10
            )
        )
        // Huge 4 GB movie with tiny 5% CRF trim -> skip to avoid copying 4 GB for 5 seconds
        assertFalse(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.TRIM_VIDEO,
                trimSubMode = TrimSubMode.CRF_AUDIO_COPY,
                sourceBytes = 4 * gb,
                trimCoverageRatio = 0.05
            )
        )
        // Huge 4 GB movie with large 60% CRF trim -> eligible
        assertTrue(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.TRIM_VIDEO,
                trimSubMode = TrimSubMode.CRF_AUDIO_COPY,
                sourceBytes = 4 * gb,
                trimCoverageRatio = 0.60
            )
        )
    }

    @Test
    fun turboHardwareModeStagesWhenStorageAllows() {
        val decision = LocalInputCachePolicy.decide(
            uriScheme = "content",
            mode = ProcessingMode.WHATSAPP_100MB,
            sourceBytes = 800 * mb,
            availableStorageBytes = 20 * gb,
            totalStorageBytes = 128 * gb,
            isHardwareTurbo = true,
            targetMb = 100.0
        )
        assertEquals(LocalInputCachePolicy.StagingDecision.STAGE_TO_CACHE, decision)
    }

    @Test
    fun customCommandProbeOnlySkipsWhileMultiStepStages() {
        assertFalse(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.CUSTOM_COMMAND,
                sourceBytes = 200 * mb,
                customStepCount = 1,
                customIsProbeOnly = true
            )
        )
        assertTrue(
            LocalInputCachePolicy.isModeEligibleForStaging(
                mode = ProcessingMode.CUSTOM_COMMAND,
                sourceBytes = 3 * gb,
                customStepCount = 2,
                customIsProbeOnly = false
            )
        )
    }

    @Test
    fun stagedInputFileNameHelperDetectsTempInputForCustomCmdExclusion() {
        assertTrue(LocalInputCachePolicy.isStagedInputFileName("fflab_staged_input.mp4"))
        assertTrue(LocalInputCachePolicy.isStagedInputFileName("FFLAB_STAGED_INPUT.MKV"))
        assertFalse(LocalInputCachePolicy.isStagedInputFileName("output_video.mp4"))
    }
}
