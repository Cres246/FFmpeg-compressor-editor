package com.example.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure, unit-testable policy rules for Android 14–16 foreground service types, sliding WakeLock
 * leases, and stall/freeze watchdog cadence across aggressive OEM ROMs (Honor MagicOS, HyperOS,
 * ColorOS, One UI 8).
 */
internal object BackgroundExecutionPolicy {
    /** Each WakeLock lease lasts 15 minutes and is overlap-renewed every 5 minutes so Honor's
     *  HwPFWService 60-minute stuck-wakelock killer is never tripped and dropped leases recover fast. */
    const val WAKELOCK_LEASE_MS = 15L * 60L * 1000L
    const val WAKELOCK_RENEW_INTERVAL_MS = 5L * 60L * 1000L
    const val WATCHDOG_INTERVAL_MS = 2_000L
    const val SILENT_NOTIFY_REFRESH_MS = 12_000L

    /**
     * Returns the prioritized foreground service type masks to attempt for [sdkInt].
     * - Android 15+ (API 35+): `mediaProcessing | mediaPlayback` -> `mediaPlayback` -> `dataSync`
     * - Android 10–14 (API 29–34): `mediaPlayback | dataSync` -> `mediaPlayback` -> `dataSync`
     * - Android 7–9 (API 24–28): empty list (uses legacy 2-arg `startForeground`)
     */
    fun resolveForegroundServiceTypes(sdkInt: Int): List<Int> {
        if (sdkInt < Build.VERSION_CODES.Q) return emptyList()
        return if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    /**
     * When Android 15+ invokes `Service.onTimeout(startId, fgsType)` after the 6-hour cumulative
     * `mediaProcessing` / `dataSync` cap, returns a non-time-limited fallback type (`mediaPlayback`)
     * so an active marathon encode can transition cleanly instead of crashing with RemoteServiceException.
     */
    fun resolveTimeoutFallbackType(sdkInt: Int, timedOutFgsType: Int): Int? {
        if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val isTimeLimited = (timedOutFgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) != 0 ||
            (timedOutFgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0
        return if (isTimeLimited) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else null
    }

    fun shouldRenewWakeLock(
        lastAcquiredElapsedMs: Long,
        nowElapsedMs: Long,
        isHeld: Boolean
    ): Boolean {
        if (!isHeld || lastAcquiredElapsedMs <= 0L) return true
        return (nowElapsedMs - lastAcquiredElapsedMs) >= WAKELOCK_RENEW_INTERVAL_MS
    }

    fun shouldRefreshNotification(
        lastNotifyElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        if (lastNotifyElapsedMs <= 0L) return true
        return (nowElapsedMs - lastNotifyElapsedMs) >= SILENT_NOTIFY_REFRESH_MS
    }
}

/**
 * Multi-layer anti-freeze & full-speed background execution guard for active encoding sessions:
 *
 * 1. **Zero-Interference Silent AudioTrack Anchor:** Keeps an all-zero 16-bit mono PCM static
 *    looping AudioTrack active in AudioFlinger (`USAGE_MEDIA`, tiny non-zero gain `0.001f` over
 *    bit-exact `0x0000` samples = mathematically exact digital silence, ZERO `requestAudioFocus()`,
 *    ZERO `MediaSession`). Spotify, YouTube, calls, and Honor Magic Capsule are completely
 *    untouched, while Honor MagicOS (`HwFastFreezer` / `iAware`), HyperOS (`Millet`), ColorOS
 *    (`Hans`), One UI (`Freecess`), and Android 16's Cached App Freezer see an active media
 *    playback PID and refuse to freeze the cgroup or banish threads to Little cores.
 * 2. **Sliding Renewable CPU WakeLock:** Holds a 15-minute `PARTIAL_WAKE_LOCK` overlap-renewed
 *    every 5 minutes so the CPU stays awake when backgrounded or locked without tripping Honor
 *    `HwPFWService`'s 60-minute continuous-hold watchdog.
 * 3. **Self-Healing Watchdog Loop (2s):** Detects if the OS/OEM stripped the WakeLock or halted
 *    the silent AudioTrack and restores both immediately, while nudging the service to keep its
 *    ongoing notification alive during silent lookahead passes.
 *
 * Strictly active ONLY while an encoding job is running and 100% released on stop/cancel/destroy.
 */
class BackgroundExecutionGuard(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var active = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockAcquireElapsedMs: Long = 0L
    private var silentAudioTrack: AudioTrack? = null
    private var watchdogJob: Job? = null

    fun start(
        scope: CoroutineScope,
        onHeartbeat: () -> Unit = {}
    ) {
        synchronized(lock) {
            active = true
            renewWakeLockLocked(force = true)
            ensureSilentAudioRunningLocked()
            watchdogJob?.cancel()
            watchdogJob = scope.launch(Dispatchers.Default) {
                while (isActive && active) {
                    delay(BackgroundExecutionPolicy.WATCHDOG_INTERVAL_MS)
                    if (!active) break
                    synchronized(lock) {
                        if (!active) return@synchronized
                        val now = SystemClock.elapsedRealtime()
                        val held = runCatching { wakeLock?.isHeld == true }.getOrDefault(false)
                        if (BackgroundExecutionPolicy.shouldRenewWakeLock(lastWakeLockAcquireElapsedMs, now, held)) {
                            renewWakeLockLocked(force = true)
                        }
                        ensureSilentAudioRunningLocked()
                    }
                    try {
                        onHeartbeat()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Never allow heartbeat callback failure to stop the keep-alive guard
                    }
                }
            }
        }
    }

    /**
     * Immediately verifies and restores the WakeLock + silent AudioTrack anchor if an encoding job
     * is active (e.g., on `onTaskRemoved` or when returning from OEM background pressure).
     */
    fun ensureAlive() {
        synchronized(lock) {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            val held = runCatching { wakeLock?.isHeld == true }.getOrDefault(false)
            if (BackgroundExecutionPolicy.shouldRenewWakeLock(lastWakeLockAcquireElapsedMs, now, held)) {
                renewWakeLockLocked(force = true)
            }
            ensureSilentAudioRunningLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            active = false
            watchdogJob?.cancel()
            watchdogJob = null
            releaseSilentAudioLocked()
            releaseWakeLockLocked()
        }
    }

    private fun renewWakeLockLocked(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val oldLock = wakeLock
        val alreadyHeld = runCatching { oldLock?.isHeld == true }.getOrDefault(false)
        if (!force && !BackgroundExecutionPolicy.shouldRenewWakeLock(lastWakeLockAcquireElapsedMs, now, alreadyHeld)) {
            return
        }
        // Overlap-acquire a fresh WakeLock BEFORE releasing the old one so there is 0ms uncovered gap
        val newLock = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching null
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${context.packageName}:MediaProcessingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(BackgroundExecutionPolicy.WAKELOCK_LEASE_MS)
            }
        }.getOrNull()

        if (newLock != null) {
            wakeLock = newLock
            lastWakeLockAcquireElapsedMs = now
            if (oldLock != null && oldLock !== newLock) {
                runCatching { if (oldLock.isHeld) oldLock.release() }
            }
        } else if (oldLock != null && !alreadyHeld) {
            runCatching {
                oldLock.acquire(BackgroundExecutionPolicy.WAKELOCK_LEASE_MS)
                lastWakeLockAcquireElapsedMs = now
            }
        }
    }

    private fun releaseWakeLockLocked() {
        val existing = wakeLock
        wakeLock = null
        lastWakeLockAcquireElapsedMs = 0L
        if (existing != null) {
            runCatching { if (existing.isHeld) existing.release() }
        }
    }

    private fun ensureSilentAudioRunningLocked() {
        val existing = silentAudioTrack
        if (existing != null) {
            val healthy = runCatching {
                existing.state == AudioTrack.STATE_INITIALIZED &&
                    existing.playState == AudioTrack.PLAYSTATE_PLAYING
            }.getOrDefault(false)
            if (healthy) return
            releaseSilentAudioLocked()
        }
        silentAudioTrack = createAndStartSilentAudioTrack()
    }

    private fun createAndStartSilentAudioTrack(): AudioTrack? {
        val nativeRate = runCatching {
            AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC)
        }.getOrDefault(48_000).takeIf { it in 8_000..48_000 } ?: 48_000

        return listOf(nativeRate, 44_100, 16_000)
            .distinct()
            .firstNotNullOfOrNull { rate -> tryStartSilentTrack(rate) }
    }

    private fun tryStartSilentTrack(sampleRate: Int): AudioTrack? {
        return runCatching {
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bytesPerFrame = 2 // Mono 16-bit PCM
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuf <= 0) return null

            val rawBufferBytes = maxOf(minBuf * 2, (sampleRate / 4) * bytesPerFrame)
            val bufferBytes = rawBufferBytes - (rawBufferBytes % bytesPerFrame)
            if (bufferBytes <= 0) return null

            val silencePcm = ByteArray(bufferBytes) // All 0x0000 -> mathematically bit-exact silence

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            // MODE_STATIC starts in STATE_NO_STATIC_DATA until static PCM data is written;
            // only STATE_UNINITIALIZED indicates a genuine builder/HAL initialization failure.
            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                runCatching { track.release() }
                return null
            }

            val writtenBytes = track.write(silencePcm, 0, silencePcm.size)
            val writtenFrames = writtenBytes / bytesPerFrame
            if (writtenFrames <= 0 || track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return null
            }

            val loopStatus = track.setLoopPoints(0, writtenFrames, -1)
            if (loopStatus != AudioTrack.SUCCESS) {
                runCatching { track.release() }
                return null
            }

            // All-zero PCM * 0.001f = exact 0.0 digital output (zero audible sound/noise),
            // while non-zero gain ensures AudioFlinger & OEM freezers treat this PID as active.
            track.setVolume(0.001f)
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.stop() }
                runCatching { track.release() }
                return null
            }
            track
        }.getOrNull()
    }

    private fun releaseSilentAudioLocked() {
        val existing = silentAudioTrack
        silentAudioTrack = null
        if (existing != null) {
            runCatching { existing.pause() }
            runCatching { existing.flush() }
            runCatching { existing.stop() }
            runCatching { existing.release() }
        }
    }
}
