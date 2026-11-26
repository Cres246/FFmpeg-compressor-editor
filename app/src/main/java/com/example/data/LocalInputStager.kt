package com.example.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import com.example.domain.FfmpegMath
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.TrimSubMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Pure, unit-testable policy deciding whether an input media file should be staged into the
 * job's sandboxed `workDir` (app-private cache) before encoding so FFmpeg / MediaCodec can read
 * directly via POSIX + Linux Page Cache instead of Android SAF/FUSE IPC — while NEVER requesting
 * storage permissions and NEVER risking filling device storage.
 */
object LocalInputCachePolicy {
    const val STAGED_INPUT_PREFIX = "fflab_staged_input"

    /** Never let device storage drop below at least 1.5 GB – 3.0 GB free (10% of total, clamped). */
    const val MIN_SAFETY_RESERVE_BYTES = 1_500_000_000L
    const val MAX_SAFETY_RESERVE_BYTES = 3_000_000_000L
    const val DEFAULT_SAFETY_RESERVE_BYTES = 2_000_000_000L

    /** Hard mid-copy floor: if free space drops below 1 GB mid-copy, abort & fall back to saf:. */
    const val MIN_RUNTIME_FREE_BYTES = 1_000_000_000L

    /** Avoid copying huge movies (> 1.5 GB) when only trimming a short slice (< 35%). */
    const val LARGE_FILE_SHORT_TRIM_THRESHOLD_BYTES = 1_500_000_000L
    const val SHORT_TRIM_MIN_COVERAGE_RATIO = 0.35

    /** Upper cap for conservative CRF/unknown output size estimate in headroom calculation. */
    private const val MAX_CONSERVATIVE_OUTPUT_ESTIMATE_BYTES = 4_000_000_000L
    private const val MIN_TARGET_OUTPUT_ESTIMATE_BYTES = 16_000_000L

    enum class StagingDecision {
        STAGE_TO_CACHE,
        ALREADY_DIRECT_FILE,
        SKIP_FALLBACK_SAF
    }

    fun computeSafetyReserveBytes(totalStorageBytes: Long): Long {
        if (totalStorageBytes <= 0L) return DEFAULT_SAFETY_RESERVE_BYTES
        return (totalStorageBytes / 10L).coerceIn(MIN_SAFETY_RESERVE_BYTES, MAX_SAFETY_RESERVE_BYTES)
    }

    fun estimateOutputBytes(
        mode: ProcessingMode,
        sourceBytes: Long,
        targetMb: Double?
    ): Long {
        val safeSource = sourceBytes.coerceAtLeast(0L)
        return when (mode) {
            ProcessingMode.DISCORD_20MB -> (20.0 * 1_048_576.0 * 1.2).toLong()
            ProcessingMode.GMAIL_25MB -> (25.0 * 1_048_576.0 * 1.2).toLong()
            ProcessingMode.WHATSAPP_100MB -> (100.0 * 1_048_576.0 * 1.2).toLong()
            ProcessingMode.CUSTOM_SIZE -> {
                val mb = (targetMb ?: 8.0).coerceAtLeast(1.0)
                (mb * 1_048_576.0 * 1.2).toLong().coerceAtLeast(MIN_TARGET_OUTPUT_ESTIMATE_BYTES)
            }
            else -> safeSource.coerceAtMost(MAX_CONSERVATIVE_OUTPUT_ESTIMATE_BYTES)
        }
    }

    fun computeRequiredFreeBytes(
        mode: ProcessingMode,
        sourceBytes: Long,
        totalStorageBytes: Long,
        targetMb: Double? = null
    ): Long {
        if (sourceBytes <= 0L) return Long.MAX_VALUE
        val reserve = computeSafetyReserveBytes(totalStorageBytes)
        val outEst = estimateOutputBytes(mode, sourceBytes, targetMb)
        // Guard against Long overflow
        val sum1 = if (sourceBytes > Long.MAX_VALUE - outEst) Long.MAX_VALUE else sourceBytes + outEst
        return if (sum1 > Long.MAX_VALUE - reserve) Long.MAX_VALUE else sum1 + reserve
    }

    fun isModeEligibleForStaging(
        mode: ProcessingMode,
        trimSubMode: TrimSubMode = TrimSubMode.FULL_LOSSLESS,
        isHardwareTurbo: Boolean = false,
        sourceBytes: Long = 0L,
        trimCoverageRatio: Double? = null,
        customStepCount: Int = 1,
        customIsProbeOnly: Boolean = false
    ): Boolean {
        if (isHardwareTurbo) {
            if (mode == ProcessingMode.TRIM_VIDEO &&
                sourceBytes > LARGE_FILE_SHORT_TRIM_THRESHOLD_BYTES &&
                trimCoverageRatio != null &&
                trimCoverageRatio < SHORT_TRIM_MIN_COVERAGE_RATIO
            ) {
                return false
            }
            return true
        }
        return when (mode) {
            // 2-Pass ABR modes benefit massively from Linux Page Cache (Pass 1 warms RAM for Pass 2)
            ProcessingMode.DISCORD_20MB,
            ProcessingMode.WHATSAPP_100MB,
            ProcessingMode.GMAIL_25MB,
            ProcessingMode.CUSTOM_SIZE -> true

            // Compute-heavy single-pass CRF software encodes benefit from zero-FUSE POSIX reads
            ProcessingMode.VIDEO_ONLY_CRF,
            ProcessingMode.VIDEO_AND_AUDIO_CRF -> true

            // Stream-copy & audio-only modes are I/O-bound: copying whole video first adds net disk I/O
            ProcessingMode.REMUX_FORMAT,
            ProcessingMode.EXTRACT_AUDIO,
            ProcessingMode.AUDIO_ONLY_AAC -> false

            // Trim: skip lossless stream copies; stage CRF trims unless copying a huge file for a tiny clip
            ProcessingMode.TRIM_VIDEO -> {
                val isCrfTrim = trimSubMode == TrimSubMode.CRF_AUDIO_COPY ||
                    trimSubMode == TrimSubMode.CRF_AUDIO_AAC
                if (!isCrfTrim) {
                    false
                } else if (sourceBytes > LARGE_FILE_SHORT_TRIM_THRESHOLD_BYTES &&
                    trimCoverageRatio != null &&
                    trimCoverageRatio < SHORT_TRIM_MIN_COVERAGE_RATIO
                ) {
                    false
                } else {
                    true
                }
            }

            // Custom commands: skip probe-only; stage multi-step commands or files <= 1.5 GB
            ProcessingMode.CUSTOM_COMMAND -> {
                if (customIsProbeOnly) false
                else customStepCount > 1 || sourceBytes <= LARGE_FILE_SHORT_TRIM_THRESHOLD_BYTES
            }
        }
    }

    fun decide(
        uriScheme: String?,
        mode: ProcessingMode,
        sourceBytes: Long,
        availableStorageBytes: Long,
        totalStorageBytes: Long,
        trimSubMode: TrimSubMode = TrimSubMode.FULL_LOSSLESS,
        isHardwareTurbo: Boolean = false,
        targetMb: Double? = null,
        trimCoverageRatio: Double? = null,
        customStepCount: Int = 1,
        customIsProbeOnly: Boolean = false
    ): StagingDecision {
        if (uriScheme.equals("file", ignoreCase = true)) {
            return StagingDecision.ALREADY_DIRECT_FILE
        }
        if (sourceBytes <= 0L || availableStorageBytes <= 0L) {
            return StagingDecision.SKIP_FALLBACK_SAF
        }
        val modeEligible = isModeEligibleForStaging(
            mode = mode,
            trimSubMode = trimSubMode,
            isHardwareTurbo = isHardwareTurbo,
            sourceBytes = sourceBytes,
            trimCoverageRatio = trimCoverageRatio,
            customStepCount = customStepCount,
            customIsProbeOnly = customIsProbeOnly
        )
        if (!modeEligible) return StagingDecision.SKIP_FALLBACK_SAF

        val requiredFree = computeRequiredFreeBytes(
            mode = mode,
            sourceBytes = sourceBytes,
            totalStorageBytes = totalStorageBytes,
            targetMb = targetMb
        )
        return if (availableStorageBytes >= requiredFree) {
            StagingDecision.STAGE_TO_CACHE
        } else {
            StagingDecision.SKIP_FALLBACK_SAF
        }
    }

    fun isStagedInputFileName(fileName: String): Boolean {
        return fileName.lowercase(Locale.US).startsWith(STAGED_INPUT_PREFIX)
    }
}

data class StagedInputResult(
    val file: File,
    val bytesCopied: Long,
    val elapsedMs: Long
)

object LocalInputStager {
    private const val COPY_BUFFER_BYTES = 1024 * 1024 // 1 MB sequential chunks
    private const val STORAGE_RECHECK_INTERVAL_BYTES = 32L * 1024L * 1024L // Re-verify free space every 32 MB

    /**
     * Evaluates zero-permission `StatFs` free space on the app's private cache volume and, when
     * safe and beneficial, sequentially copies the source URI into `workDir/fflab_staged_input.<ext>`
     * so FFmpeg / MediaCodec reads via direct POSIX + Linux Page Cache.
     *
     * Safety guarantees:
     * - Zero storage permissions required (`StatFs` inspects app-private `workDir`).
     * - Leaves at least 1.5–3.0 GB free on device storage; otherwise silently returns `null`.
     * - Any I/O error or sudden low-space condition deletes partial files and silently returns `null`.
     * - Cancellation deletes partial files immediately and rethrows `CancellationException`.
     * - Lives strictly inside `workDir` (`fflab_work_*`), automatically wiped by job `finally`
     *   and orphan startup cleanup.
     */
    suspend fun stageInputIfEligible(
        context: Context,
        media: MediaInfo,
        config: ModeConfigState,
        workDir: File,
        isHardwareTurbo: Boolean = false,
        customStepCount: Int = 1,
        customIsProbeOnly: Boolean = false,
        isStillActive: () -> Boolean = { true },
        onBeforeCopy: (() -> Unit)? = null
    ): StagedInputResult? = withContext(Dispatchers.IO) {
        val uri: Uri = media.uri
        val trimCoverageRatio = if (config.mode == ProcessingMode.TRIM_VIDEO && (media.durationSec ?: 0.0) > 0.0) {
            val clipDur = FfmpegMath.resolveTrimRange(config, media.durationSec).third
            if (clipDur != null && clipDur > 0.0) {
                (clipDur / media.durationSec!!).coerceIn(0.0, 1.0)
            } else {
                null
            }
        } else {
            null
        }
        val targetMb = when (config.mode) {
            ProcessingMode.DISCORD_20MB -> 20.0
            ProcessingMode.GMAIL_25MB -> 25.0
            ProcessingMode.WHATSAPP_100MB -> 100.0
            ProcessingMode.CUSTOM_SIZE -> FfmpegMath.parsePositiveDouble(config.targetMbInput) ?: 8.0
            else -> null
        }

        val statFs = runCatching { StatFs(workDir.absolutePath) }.getOrNull() ?: return@withContext null
        val availableBytes = runCatching { statFs.availableBytes }.getOrDefault(0L)
        val totalBytes = runCatching { statFs.totalBytes }.getOrDefault(0L)

        val decision = LocalInputCachePolicy.decide(
            uriScheme = uri.scheme,
            mode = config.mode,
            sourceBytes = media.sizeBytes,
            availableStorageBytes = availableBytes,
            totalStorageBytes = totalBytes,
            trimSubMode = config.trimSubMode,
            isHardwareTurbo = isHardwareTurbo,
            targetMb = targetMb,
            trimCoverageRatio = trimCoverageRatio,
            customStepCount = customStepCount,
            customIsProbeOnly = customIsProbeOnly
        )
        if (decision != LocalInputCachePolicy.StagingDecision.STAGE_TO_CACHE) {
            return@withContext null
        }

        val safeExt = media.extension
            .lowercase(Locale.US)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(8)
            .ifBlank { "bin" }
        val stagedFile = File(workDir, "${LocalInputCachePolicy.STAGED_INPUT_PREFIX}.$safeExt")
        runCatching { if (stagedFile.exists()) stagedFile.delete() }

        onBeforeCopy?.invoke()
        val startMs = SystemClock.elapsedRealtime()
        var totalWritten = 0L
        var bytesSinceRecheck = 0L

        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            inputStream.use { input ->
                FileOutputStream(stagedFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (!isStillActive()) {
                            runCatching { stagedFile.delete() }
                            return@withContext null
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        totalWritten += read
                        bytesSinceRecheck += read

                        if (bytesSinceRecheck >= STORAGE_RECHECK_INTERVAL_BYTES) {
                            bytesSinceRecheck = 0L
                            val liveFree = runCatching { StatFs(workDir.absolutePath).availableBytes }
                                .getOrDefault(0L)
                            if (liveFree < LocalInputCachePolicy.MIN_RUNTIME_FREE_BYTES) {
                                runCatching { output.close() }
                                runCatching { stagedFile.delete() }
                                return@withContext null
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (totalWritten <= 0L || !stagedFile.exists() || stagedFile.length() <= 0L) {
                runCatching { stagedFile.delete() }
                return@withContext null
            }
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            StagedInputResult(
                file = stagedFile,
                bytesCopied = totalWritten,
                elapsedMs = elapsedMs
            )
        } catch (ce: CancellationException) {
            runCatching { stagedFile.delete() }
            throw ce
        } catch (_: Throwable) {
            // Silent automatic fallback to zero-copy saf: on any I/O or space failure
            runCatching { stagedFile.delete() }
            null
        }
    }
}
