package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs

enum class PreviewQuality {
    /** Fast low-res keyframe preview (256px, RGB_565, OPTION_CLOSEST_SYNC) for fluid 120Hz scrubbing. */
    FAST_SCRUB,
    /** Full-detail frame-accurate preview (480px, ARGB_8888, OPTION_CLOSEST) when settled or lifted. */
    EXACT_CRISP
}

data class CachedPreviewFrame(
    val bitmap: Bitmap,
    val timestampSec: Double,
    val isCrisp: Boolean
)

/**
 * Pure, JVM unit-testable math & argument builders for progressive trim frame scrubbing.
 */
object TrimPreviewMath {

    const val FAST_SCRUB_MAX_DIM = 256
    const val EXACT_CRISP_MAX_DIM = 480
    const val CRISP_BUCKET_MS = 50L

    fun clampPreviewSec(timeSec: Double, durationSec: Double?): Double {
        if (!timeSec.isFinite()) return 0.0
        val maxSec = durationSec
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it - 0.25).coerceAtLeast(0.0) }
        return if (maxSec != null) {
            timeSec.coerceIn(0.0, maxSec)
        } else {
            timeSec.coerceAtLeast(0.0)
        }
    }

    /**
     * Quantization bucket size during active scrubbing: 100ms on short clips, up to 1000ms on long videos,
     * eliminating redundant decodes of identical keyframes during rapid dragging.
     */
    fun scrubBucketMs(durationSec: Double?): Long {
        if (durationSec == null || !durationSec.isFinite() || durationSec <= 0.0) return 250L
        return ((durationSec * 1000.0) / 120.0).toLong().coerceIn(100L, 1_000L)
    }

    /**
     * Maximum distance from target timestamp for showing a pre-warmed / previously cached nearby frame
     * at 0ms while the exact target frame decodes in the background.
     */
    fun scrubToleranceMs(durationSec: Double?): Long {
        if (durationSec == null || !durationSec.isFinite() || durationSec <= 0.0) return 3_000L
        return ((durationSec * 1000.0) / 18.0).toLong().coerceIn(1_200L, 12_000L)
    }

    fun quantizeBucketMs(timeSec: Double, bucketMs: Long): Long {
        val safeBucket = bucketMs.coerceAtLeast(1L)
        val ms = (timeSec.coerceAtLeast(0.0) * 1000.0).toLong()
        return ((ms + safeBucket / 2L) / safeBucket) * safeBucket
    }

    /**
     * Computes evenly distributed interior sample timestamps across the video timeline for silent
     * background pre-warming (excluding 0% and 100% which are loaded immediately by Start/End boxes).
     */
    fun computePrewarmTimestamps(durationSec: Double?, maxSamples: Int = 24): List<Double> {
        if (durationSec == null || !durationSec.isFinite() || durationSec <= 1.0 || maxSamples <= 0) {
            return emptyList()
        }
        val count = minOf(maxSamples, (durationSec / 0.5).toInt().coerceAtLeast(4))
        val bucketMs = scrubBucketMs(durationSec)
        val seenBuckets = LinkedHashSet<Long>(count)
        val result = ArrayList<Double>(count)
        for (i in 1..count) {
            val rawSec = (durationSec * i.toDouble()) / (count + 1).toDouble()
            val clamped = clampPreviewSec(rawSec, durationSec)
            val bucket = quantizeBucketMs(clamped, bucketMs)
            if (seenBuckets.add(bucket)) {
                result.add(clamped)
            }
        }
        return result
    }

    /**
     * Finds the cached bucket key closest to [targetMs] within [maxDeltaMs], or null if none qualify.
     */
    fun findNearestCachedKey(
        targetMs: Long,
        cachedKeys: Collection<Long>,
        maxDeltaMs: Long
    ): Long? {
        if (cachedKeys.isEmpty() || maxDeltaMs < 0L) return null
        var bestKey: Long? = null
        var bestDelta = Long.MAX_VALUE
        for (key in cachedKeys) {
            val delta = abs(key - targetMs)
            if (delta <= maxDeltaMs && delta < bestDelta) {
                bestDelta = delta
                bestKey = key
            }
        }
        return bestKey
    }

    /**
     * Builds single-frame FFmpeg fallback arguments for containers unsupported by MediaMetadataRetriever
     * (e.g. MKV, 10-bit HEVC, AV1). In [fastScrub] mode, `-noaccurate_seek` jumps straight to the
     * keyframe without decoding the full GOP and scales to 256px using `fast_bilinear`.
     */
    fun buildFfmpegThumbArgs(
        safPath: String,
        sec: Double,
        outputPath: String,
        fastScrub: Boolean
    ): Array<String> {
        val ts = String.format(Locale.US, "%.3f", sec.coerceAtLeast(0.0))
        val maxDim = if (fastScrub) FAST_SCRUB_MAX_DIM else EXACT_CRISP_MAX_DIM
        val quality = if (fastScrub) "8" else "4"
        return buildList {
            add("-hide_banner")
            add("-loglevel")
            add("error")
            if (fastScrub) {
                add("-noaccurate_seek")
            }
            add("-ss")
            add(ts)
            add("-i")
            add(safPath)
            add("-an")
            add("-sn")
            add("-dn")
            add("-frames:v")
            add("1")
            add("-vf")
            add("scale='min($maxDim,iw)':-2:flags=fast_bilinear")
            add("-q:v")
            add(quality)
            add("-threads")
            add("2")
            add("-y")
            add(outputPath)
        }.toTypedArray()
    }
}

/**
 * High-speed, persistent video frame scrubber for Trim previews:
 * 1. Keeps a single [MediaMetadataRetriever] + [ParcelFileDescriptor] open for the active Trim
 *    session so MP4/MOV `moov`/`stbl` tables and hardware decoder state are parsed only ONCE.
 * 2. Progressive two-stage decode:
 *    - [PreviewQuality.FAST_SCRUB]: `256px` `RGB_565` (API 30+) + `OPTION_CLOSEST_SYNC` (~8–20ms).
 *    - [PreviewQuality.EXACT_CRISP]: `480px` `ARGB_8888` + `OPTION_CLOSEST` when settled/released.
 * 3. Two-tier in-memory [LruCache] (6 MB scrub tier + 8 MB crisp tier) with silent background
 *    timeline pre-warming that immediately yields to interactive user scrubbing.
 * 4. Cancellable, `-noaccurate_seek` + `fast_bilinear` FFmpeg fallback for MKV / 10-bit HEVC.
 * 5. Guaranteed VPU/FD release on [close] (bound to `isActiveStage`) so zero hardware decoders
 *    linger when leaving Trim or launching FFmpeg/MediaCodec encoding.
 */
class VideoFrameScrubber(
    context: Context,
    val uri: Uri,
    val durationSec: Double?
) : Closeable {

    private val appContext: Context = context.applicationContext
    private val scrubBucketMs: Long = TrimPreviewMath.scrubBucketMs(durationSec)
    private val scrubToleranceMs: Long = TrimPreviewMath.scrubToleranceMs(durationSec)

    private val retrieverMutex = Mutex()
    private val ffmpegMutex = Mutex()

    private var retriever: MediaMetadataRetriever? = null
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var retrieverUnsupported: Boolean = false

    private var consecutiveRetrieverNulls: Int = 0

    @Volatile
    private var closed: Boolean = false

    private val activeUserRequests = AtomicInteger(0)
    private val activeFfmpegSessionId = AtomicLong(-1L)

    private val scrubKeys = ConcurrentHashMap.newKeySet<Long>()
    private val crispKeys = ConcurrentHashMap.newKeySet<Long>()

    // Never call bitmap.recycle() in entryRemoved: Compose Image may still be drawing an evicted frame.
    private val scrubCache = object : LruCache<Long, Bitmap>(SCRUB_CACHE_KB) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) {
                scrubKeys.remove(key)
            }
        }
    }

    private val crispCache = object : LruCache<Long, Bitmap>(CRISP_CACHE_KB) {
        override fun sizeOf(key: Long, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) {
                crispKeys.remove(key)
            }
        }
    }

    /**
     * 0ms synchronous RAM lookup: checks exact crisp -> exact scrub -> (optional) nearest cached
     * frame within [scrubToleranceMs] so fast scrubbing gets instant 120Hz visual feedback.
     */
    fun peekInstantFrame(timeSec: Double, allowNearbyFallback: Boolean): CachedPreviewFrame? {
        if (closed) return null
        val clampedSec = TrimPreviewMath.clampPreviewSec(timeSec, durationSec)
        val crispKey = TrimPreviewMath.quantizeBucketMs(clampedSec, TrimPreviewMath.CRISP_BUCKET_MS)
        crispCache.get(crispKey)?.let { bmp ->
            return CachedPreviewFrame(bmp, crispKey / 1000.0, isCrisp = true)
        }

        val scrubKey = TrimPreviewMath.quantizeBucketMs(clampedSec, scrubBucketMs)
        scrubCache.get(scrubKey)?.let { bmp ->
            return CachedPreviewFrame(bmp, scrubKey / 1000.0, isCrisp = false)
        }

        if (!allowNearbyFallback) return null

        val targetMs = (clampedSec * 1000.0).toLong()
        val nearestCrispKey = TrimPreviewMath.findNearestCachedKey(targetMs, crispKeys, scrubToleranceMs)
        val nearestScrubKey = TrimPreviewMath.findNearestCachedKey(targetMs, scrubKeys, scrubToleranceMs)

        val crispDelta = nearestCrispKey?.let { abs(it - targetMs) } ?: Long.MAX_VALUE
        val scrubDelta = nearestScrubKey?.let { abs(it - targetMs) } ?: Long.MAX_VALUE

        return if (crispDelta <= scrubDelta && nearestCrispKey != null) {
            crispCache.get(nearestCrispKey)?.let { bmp ->
                CachedPreviewFrame(bmp, nearestCrispKey / 1000.0, isCrisp = true)
            }
        } else if (nearestScrubKey != null) {
            scrubCache.get(nearestScrubKey)?.let { bmp ->
                CachedPreviewFrame(bmp, nearestScrubKey / 1000.0, isCrisp = false)
            }
        } else {
            null
        }
    }

    fun hasCrispFrame(timeSec: Double): Boolean {
        if (closed) return false
        val clampedSec = TrimPreviewMath.clampPreviewSec(timeSec, durationSec)
        val crispKey = TrimPreviewMath.quantizeBucketMs(clampedSec, TrimPreviewMath.CRISP_BUCKET_MS)
        return crispCache.get(crispKey) != null
    }

    fun hasExactScrubFrame(timeSec: Double): Boolean {
        if (closed) return false
        val clampedSec = TrimPreviewMath.clampPreviewSec(timeSec, durationSec)
        val scrubKey = TrimPreviewMath.quantizeBucketMs(clampedSec, scrubBucketMs)
        return scrubCache.get(scrubKey) != null
    }

    suspend fun extractFrame(timeSec: Double, quality: PreviewQuality): Bitmap? {
        if (closed) return null
        val clampedSec = TrimPreviewMath.clampPreviewSec(timeSec, durationSec)
        getCachedExact(clampedSec, quality)?.let { return it }

        activeUserRequests.incrementAndGet()
        return try {
            withContext(Dispatchers.IO) {
                decodeInternal(clampedSec, quality, isPrewarm = false)
            }
        } finally {
            activeUserRequests.decrementAndGet()
        }
    }

    /**
     * Quietly pre-warms evenly spaced [PreviewQuality.FAST_SCRUB] thumbnails across the timeline
     * after initial Start/End frames load. Yields immediately whenever interactive user scrubbing
     * occurs and skips software FFmpeg pre-warming on MKV / 10-bit HEVC to keep CPU/battery cool.
     */
    suspend fun prewarmTimeline(maxSamples: Int = 24) {
        val samples = TrimPreviewMath.computePrewarmTimestamps(durationSec, maxSamples)
        if (samples.isEmpty()) return

        // Let initial Start & End preview frames load and refine first with zero contention.
        delay(450L)

        withContext(Dispatchers.IO) {
            for (sampleSec in samples) {
                currentCoroutineContext().ensureActive()
                if (closed || retrieverUnsupported) return@withContext
                if (getCachedExact(sampleSec, PreviewQuality.FAST_SCRUB) != null) continue

                while (activeUserRequests.get() > 0) {
                    currentCoroutineContext().ensureActive()
                    if (closed || retrieverUnsupported) return@withContext
                    delay(80L)
                }

                decodeInternal(sampleSec, PreviewQuality.FAST_SCRUB, isPrewarm = true)
                delay(12L)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true

        val runningSessionId = activeFfmpegSessionId.getAndSet(-1L)
        if (runningSessionId > 0L) {
            runCatching { FFmpegKit.cancel(runningSessionId) }
        }

        scrubCache.evictAll()
        crispCache.evictAll()
        scrubKeys.clear()
        crispKeys.clear()

        // Serialize native retriever.release() & FD close under retrieverMutex off the UI thread
        // so it never races with an in-flight getScaledFrameAtTime call.
        cleanupScope.launch {
            retrieverMutex.withLock {
                releaseRetrieverLocked()
            }
        }
    }

    private fun getCachedExact(clampedSec: Double, quality: PreviewQuality): Bitmap? {
        val crispKey = TrimPreviewMath.quantizeBucketMs(clampedSec, TrimPreviewMath.CRISP_BUCKET_MS)
        crispCache.get(crispKey)?.let { return it }
        if (quality == PreviewQuality.FAST_SCRUB) {
            val scrubKey = TrimPreviewMath.quantizeBucketMs(clampedSec, scrubBucketMs)
            scrubCache.get(scrubKey)?.let { return it }
        }
        return null
    }

    private fun putCached(clampedSec: Double, quality: PreviewQuality, bitmap: Bitmap) {
        if (closed) return
        if (quality == PreviewQuality.EXACT_CRISP) {
            val crispKey = TrimPreviewMath.quantizeBucketMs(clampedSec, TrimPreviewMath.CRISP_BUCKET_MS)
            crispKeys.add(crispKey)
            crispCache.put(crispKey, bitmap)
        } else {
            val scrubKey = TrimPreviewMath.quantizeBucketMs(clampedSec, scrubBucketMs)
            scrubKeys.add(scrubKey)
            scrubCache.put(scrubKey, bitmap)
        }
    }

    private suspend fun decodeInternal(
        clampedSec: Double,
        quality: PreviewQuality,
        isPrewarm: Boolean
    ): Bitmap? {
        if (closed) return null
        getCachedExact(clampedSec, quality)?.let { return it }

        if (!retrieverUnsupported) {
            val hwBitmap = retrieverMutex.withLock {
                if (closed) return@withLock null
                if (isPrewarm && activeUserRequests.get() > 0) return@withLock null
                getCachedExact(clampedSec, quality)?.let { return@withLock it }
                if (retrieverUnsupported) return@withLock null

                val firstAttempt = tryDecodeWithPersistentRetrieverLocked(clampedSec, quality)
                if (firstAttempt != null) {
                    consecutiveRetrieverNulls = 0
                    putCached(clampedSec, quality, firstAttempt)
                    firstAttempt
                } else {
                    consecutiveRetrieverNulls++
                    if (consecutiveRetrieverNulls >= 2) {
                        retrieverUnsupported = true
                        releaseRetrieverLocked()
                    }
                    null
                }
            }
            if (hwBitmap != null) return hwBitmap
        }

        // Skip heavy software FFmpeg background pre-warming on MKV / 10-bit HEVC.
        if (isPrewarm || closed) return null

        val swBitmap = decodeWithFfmpegFallback(clampedSec, quality)
        if (swBitmap != null) {
            putCached(clampedSec, quality, swBitmap)
        }
        return swBitmap
    }

    private fun tryDecodeWithPersistentRetrieverLocked(
        clampedSec: Double,
        quality: PreviewQuality
    ): Bitmap? {
        val active = ensureRetrieverLocked() ?: return null
        val timeUs = (clampedSec * 1_000_000.0).toLong().coerceAtLeast(0L)
        val backoffUs = ((clampedSec - 1.0).coerceAtLeast(0.0) * 1_000_000.0).toLong()

        return try {
            decodeFromRetriever(active, timeUs, quality)
                ?: if (backoffUs < timeUs) decodeFromRetriever(active, backoffUs, quality) else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Recreate retriever once on transient VPU / codec state errors.
            releaseRetrieverLocked()
            val retry = ensureRetrieverLocked() ?: return null
            try {
                decodeFromRetriever(retry, timeUs, quality)
                    ?: if (backoffUs < timeUs) decodeFromRetriever(retry, backoffUs, quality) else null
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                releaseRetrieverLocked()
                null
            }
        }
    }

    private fun ensureRetrieverLocked(): MediaMetadataRetriever? {
        if (closed || retrieverUnsupported) return null
        retriever?.let { return it }

        val candidate = MediaMetadataRetriever()
        var openedPfd: ParcelFileDescriptor? = null
        try {
            var dataSourceReady = false
            if (!uri.scheme.equals("file", ignoreCase = true)) {
                openedPfd = runCatching {
                    appContext.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull()
                if (openedPfd != null) {
                    dataSourceReady = runCatching {
                        candidate.setDataSource(openedPfd.fileDescriptor)
                        true
                    }.getOrDefault(false)
                    if (!dataSourceReady) {
                        runCatching { openedPfd.close() }
                        openedPfd = null
                    }
                }
            }
            if (!dataSourceReady) {
                if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                    candidate.setDataSource(uri.path)
                } else {
                    candidate.setDataSource(appContext, uri)
                }
            }
            retriever = candidate
            pfd = openedPfd
            return candidate
        } catch (ce: CancellationException) {
            runCatching { candidate.release() }
            runCatching { openedPfd?.close() }
            throw ce
        } catch (_: Throwable) {
            runCatching { candidate.release() }
            runCatching { openedPfd?.close() }
            retrieverUnsupported = true
            return null
        }
    }

    private fun releaseRetrieverLocked() {
        val oldRetriever = retriever
        val oldPfd = pfd
        retriever = null
        pfd = null
        runCatching { oldRetriever?.release() }
        runCatching { oldPfd?.close() }
    }

    private fun decodeFromRetriever(
        activeRetriever: MediaMetadataRetriever,
        targetUs: Long,
        quality: PreviewQuality
    ): Bitmap? {
        return when (quality) {
            PreviewQuality.FAST_SCRUB -> {
                val maxDim = TrimPreviewMath.FAST_SCRUB_MAX_DIM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val rgb565Params = MediaMetadataRetriever.BitmapParams().apply {
                        preferredConfig = Bitmap.Config.RGB_565
                    }
                    activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxDim,
                        maxDim,
                        rgb565Params
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxDim,
                        maxDim
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        maxDim,
                        maxDim,
                        rgb565Params
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        maxDim,
                        maxDim
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxDim,
                        maxDim
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        maxDim,
                        maxDim
                    )
                } else {
                    val raw = activeRetriever.getFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: activeRetriever.getFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
                    )
                    raw?.let { downscaleIfNeeded(it, maxDim) }
                }
            }

            PreviewQuality.EXACT_CRISP -> {
                val maxDim = TrimPreviewMath.EXACT_CRISP_MAX_DIM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        maxDim,
                        maxDim
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        maxDim,
                        maxDim
                    ) ?: activeRetriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxDim,
                        maxDim
                    )
                } else {
                    val raw = activeRetriever.getFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: activeRetriever.getFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
                    ) ?: activeRetriever.getFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    raw?.let { downscaleIfNeeded(it, maxDim) }
                }
            }
        }
    }

    private fun downscaleIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) return source
        val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        if (scaled !== source) {
            source.recycle()
        }
        return scaled
    }

    private suspend fun decodeWithFfmpegFallback(
        clampedSec: Double,
        quality: PreviewQuality
    ): Bitmap? = ffmpegMutex.withLock {
        if (closed) return@withLock null
        getCachedExact(clampedSec, quality)?.let { return@withLock it }

        val fastScrub = quality == PreviewQuality.FAST_SCRUB
        runCancellableFfmpegThumb(clampedSec, fastScrub)
            ?: if (!closed && clampedSec > 1.0) {
                runCancellableFfmpegThumb((clampedSec - 1.0).coerceAtLeast(0.0), fastScrub)
            } else {
                null
            }
    }

    private suspend fun runCancellableFfmpegThumb(sec: Double, fastScrub: Boolean): Bitmap? {
        if (closed) return null
        val tmpFile = File(appContext.cacheDir, "fflab_thumb_${System.nanoTime()}.jpg")
        return try {
            FfmpegEngine.ensureSilentLogcat()
            val safPath = FfmpegEngine.resolveInputPath(appContext, uri)
            val args = TrimPreviewMath.buildFfmpegThumbArgs(
                safPath = safPath,
                sec = sec,
                outputPath = tmpFile.absolutePath,
                fastScrub = fastScrub
            )
            val succeeded = suspendCancellableCoroutine<Boolean> { cont ->
                if (closed) {
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                val session = try {
                    FFmpegKit.executeWithArgumentsAsync(
                        args,
                        { completed ->
                            activeFfmpegSessionId.compareAndSet(completed.sessionId, -1L)
                            if (cont.isActive) {
                                cont.resume(ReturnCode.isSuccess(completed.returnCode))
                            }
                        },
                        null,
                        null
                    )
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }

                activeFfmpegSessionId.set(session.sessionId)
                if (closed) {
                    activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                }
                cont.invokeOnCancellation {
                    activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                }
            }

            if (succeeded && !closed && tmpFile.isFile && tmpFile.length() > 0L) {
                val opts = BitmapFactory.Options().apply {
                    if (fastScrub) {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                }
                BitmapFactory.decodeFile(tmpFile.absolutePath, opts)
            } else {
                null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    companion object {
        private const val SCRUB_CACHE_KB = 6 * 1024
        private const val CRISP_CACHE_KB = 8 * 1024
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
