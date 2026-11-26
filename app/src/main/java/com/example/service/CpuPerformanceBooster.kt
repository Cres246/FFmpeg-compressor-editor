package com.example.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import com.example.domain.AppLanguageController
import com.example.domain.AppStrings
import com.example.domain.CpuTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sustains high multi-core throughput for both software FFmpeg (`libx264`/`libx265`) and hardware
 * Turbo (`Media3`/`DirectMediaCodec`) threads in foreground and background without fighting the
 * Linux EAS scheduler or tripping early thermal throttling:
 * 1. Samples `/proc/self/task/<tid>/stat` (single file read per thread, extracting both `(comm)`
 *    and CPU ticks at once) on a lightweight 5s cadence to identify active encoder/feeder threads.
 * 2. Keeps ALL CPU cores unlocked (Prime + Big + Little — NO `sched_setaffinity` pinning) so
 *    `x264`/`x265` and the kernel scheduler can utilize every core just like native Termux.
 * 3. Applies a clean, uniform favorable priority (`THREAD_PRIORITY_DISPLAY` / `-4`) across active
 *    encoder threads so OEM background schedulers cannot demote them to `+10` without causing
 *    internal priority skew across row/frame synchronization barriers.
 * 4. Maintains a balanced ADPF [PerformanceHintManager.Session] (`setPreferPowerEfficiency(false)`
 *    on API 35+, sustainable `16ms`/`18ms` hint) so PowerHAL sustains multi-core clocks without
 *    spiking peak voltage into premature thermal throttling.
 */
class CpuPerformanceBooster(private val context: Context) {

    enum class BoostMode {
        FFMPEG_SOFTWARE,
        TURBO_MEDIACODEC
    }

    private data class ThreadSample(
        val tid: Int,
        val comm: String,
        val ticks: Long
    )

    private var perfHintSession: PerformanceHintManager.Session? = null
    private var lastRegisteredTids: IntArray = IntArray(0)
    private var boostedPriorityTids: HashSet<Int> = hashSetOf()
    private var hasPreviousCpuSample: Boolean = false
    private var lastSampledCpuTicks: Map<Int, Long> = emptyMap()
    private var lastBoostPulseElapsedMs: Long = 0L
    private var lastSessionReassertElapsedMs: Long = 0L
    private var tickerJob: Job? = null
    private var earlyDiscoveryJob: Job? = null

    private val commonIgnoredThreadPrefixes = listOf(
        "Binder:", "RenderThread", "HeapTaskDaemon", "ReferenceQueue",
        "FinalizerDaemon", "FinalizerWatch", "Profile Saver", "Jit thread",
        "Signal Catcher", "perfetto", "hwuiTask", "ConnectivityThr", "OkHttp",
        "Okio", "AudioTrack", "AudioOut"
    )
    private val softwareOnlyIgnoredThreadPrefixes = listOf(
        "MediaCodec_", "DefaultDispatch", "AsyncTask"
    )

    private val lock = Any()

    @Volatile
    private var active: Boolean = false

    @Volatile
    private var currentMode: BoostMode = BoostMode.FFMPEG_SOFTWARE

    fun start(
        mode: BoostMode = BoostMode.FFMPEG_SOFTWARE,
        scope: CoroutineScope? = null
    ) {
        synchronized(lock) {
            active = true
            currentMode = mode
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            boostedPriorityTids.clear()
            hasPreviousCpuSample = false
            lastSampledCpuTicks = readProcessThreadSamples().associate { it.tid to it.ticks }
            lastSessionReassertElapsedMs = 0L
            refreshAndPulseLocked(force = true)

            tickerJob?.cancel()
            tickerJob = scope?.launch(Dispatchers.Default) {
                while (isActive && active) {
                    delay(TICKER_INTERVAL_MS)
                    if (!active) break
                    synchronized(lock) {
                        if (!active) return@synchronized
                        val now = SystemClock.elapsedRealtime()
                        val shouldForceSession = (now - lastSessionReassertElapsedMs) >= SESSION_REASSERT_INTERVAL_MS
                        refreshAndPulseLocked(force = shouldForceSession)
                    }
                }
            }
        }
    }

    /**
     * Triggers a single one-shot thread scan shortly after a new FFmpeg pass begins (~650ms),
     * registering newly spawned native `libx264`/`libx265` worker TIDs into ADPF and `-4`
     * priority immediately instead of waiting for the 5s steady-state ticker (critical for fast
     * Pass 1 analysis runs). Zero steady-state overhead and zero quality impact.
     */
    fun scheduleEarlyThreadDiscovery(scope: CoroutineScope) {
        synchronized(lock) {
            if (!active) return
            earlyDiscoveryJob?.cancel()
            earlyDiscoveryJob = scope.launch(Dispatchers.Default) {
                delay(EARLY_DISCOVERY_DELAY_MS)
                synchronized(lock) {
                    if (!active) return@synchronized
                    refreshAndPulseLocked(force = true)
                }
            }
        }
    }

    fun pulseIfDue() {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBoostPulseElapsedMs >= PULSE_MIN_INTERVAL_MS) {
            synchronized(lock) {
                if (!active) return
                val checkNow = SystemClock.elapsedRealtime()
                if (checkNow - lastBoostPulseElapsedMs >= PULSE_MIN_INTERVAL_MS) {
                    val shouldForceSession = (checkNow - lastSessionReassertElapsedMs) >= SESSION_REASSERT_INTERVAL_MS
                    refreshAndPulseLocked(force = shouldForceSession)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            active = false
            tickerJob?.cancel()
            tickerJob = null
            earlyDiscoveryJob?.cancel()
            earlyDiscoveryJob = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { perfHintSession?.close() }
            }
            perfHintSession = null
            lastRegisteredTids = IntArray(0)
            boostedPriorityTids.clear()
            hasPreviousCpuSample = false
            lastSampledCpuTicks = emptyMap()
            lastBoostPulseElapsedMs = 0L
            lastSessionReassertElapsedMs = 0L
        }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
    }

    private fun refreshAndPulseLocked(force: Boolean) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        lastBoostPulseElapsedMs = now
        val samples = readProcessThreadSamples()
        val mainPid = Process.myPid()
        val maxThreads = if (currentMode == BoostMode.FFMPEG_SOFTWARE) 32 else 16

        val isSoftwareFfmpeg = currentMode == BoostMode.FFMPEG_SOFTWARE

        // Rank worker threads by CPU delta since last sample; exclude idle non-encoder threads
        // once a baseline exists so ADPF tracks strictly active encoder/feeder workers.
        val candidates = samples
            .filter { sample -> sample.tid != mainPid && !isIgnoredComm(sample.comm, isSoftwareFfmpeg) }
            .map { sample ->
                val prev = lastSampledCpuTicks[sample.tid] ?: 0L
                val delta = (sample.ticks - prev).coerceAtLeast(0L)
                Triple(sample.tid, delta, sample.ticks)
            }

        val activeCandidates = if (hasPreviousCpuSample) {
            candidates.filter { it.second > 0L }.ifEmpty { candidates.filter { it.third > 0L } }
        } else {
            candidates.filter { it.third > 0L }.ifEmpty { candidates }
        }

        val rankedEncoderTids = activeCandidates
            .sortedWith(compareByDescending<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .take(maxThreads)

        lastSampledCpuTicks = samples.associate { it.tid to it.ticks }
        hasPreviousCpuSample = true

        val targetTids = if (rankedEncoderTids.isNotEmpty()) {
            rankedEncoderTids.toIntArray()
        } else {
            intArrayOf(Process.myTid())
        }

        // Uniform favorable priority across active workers (applied on discovery or periodic reassert
        // to avoid redundant syscalls while still defeating OEM background demotion).
        if (force) {
            boostedPriorityTids.clear()
        }
        targetTids.forEachIndexed { index, tid ->
            if (force || boostedPriorityTids.add(tid)) {
                if (isSoftwareFfmpeg) {
                    setHighestAllowedPriority(
                        tid = tid,
                        preferredNice = Process.THREAD_PRIORITY_DISPLAY,
                        fallbackNice = Process.THREAD_PRIORITY_MORE_FAVORABLE
                    )
                } else {
                    when {
                        index <= 3 -> setHighestAllowedPriority(
                            tid = tid,
                            preferredNice = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                            fallbackNice = Process.THREAD_PRIORITY_DISPLAY
                        )
                        else -> setHighestAllowedPriority(
                            tid = tid,
                            preferredNice = Process.THREAD_PRIORITY_DISPLAY,
                            fallbackNice = Process.THREAD_PRIORITY_MORE_FAVORABLE
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateAdpfSession(targetTids, force, now)
        }
    }

    private fun setHighestAllowedPriority(tid: Int, preferredNice: Int, fallbackNice: Int) {
        val applied = runCatching {
            Process.setThreadPriority(tid, preferredNice)
            true
        }.getOrDefault(false)
        if (!applied) {
            runCatching { Process.setThreadPriority(tid, fallbackNice) }
        }
    }

    private fun updateAdpfSession(tids: IntArray, force: Boolean, nowElapsedMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // ADPF sessions work best with the hottest threads (cap at 16 TIDs for HAL stability)
        val adpfTids = if (tids.size > 16) tids.copyOf(16) else tids
        val tidsChanged = !adpfTids.contentEquals(lastRegisteredTids)

        runCatching {
            val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
            var session = perfHintSession

            if (session == null) {
                session = createSustainedSession(phm, adpfTids)
                if (session != null) {
                    perfHintSession = session
                    lastRegisteredTids = adpfTids.copyOf()
                    lastSessionReassertElapsedMs = nowElapsedMs
                }
            } else {
                if (tidsChanged || force) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val updated = runCatching {
                            session.setThreads(adpfTids)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                session.setPreferPowerEfficiency(false)
                            }
                            true
                        }.getOrDefault(false)
                        if (updated) {
                            lastRegisteredTids = adpfTids.copyOf()
                            lastSessionReassertElapsedMs = nowElapsedMs
                        } else {
                            runCatching { session.close() }
                            session = createSustainedSession(phm, adpfTids)
                            perfHintSession = session
                            lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                            lastSessionReassertElapsedMs = nowElapsedMs
                        }
                    } else {
                        runCatching { session.close() }
                        session = createSustainedSession(phm, adpfTids)
                        perfHintSession = session
                        lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                        lastSessionReassertElapsedMs = nowElapsedMs
                    }
                }
                val actualDurationNs = currentAdpfActualDurationNs()
                val reported = runCatching {
                    session?.reportActualWorkDuration(actualDurationNs)
                    true
                }.getOrDefault(false)
                if (!reported) {
                    // Recover cleanly if OS invalidated the ADPF session during fg -> bg transition
                    runCatching { session?.close() }
                    val recreated = createSustainedSession(phm, adpfTids)
                    perfHintSession = recreated
                    lastRegisteredTids = if (recreated != null) adpfTids.copyOf() else IntArray(0)
                    lastSessionReassertElapsedMs = nowElapsedMs
                }
            }
        }
    }

    private fun currentAdpfActualDurationNs(): Long =
        if (currentMode == BoostMode.FFMPEG_SOFTWARE) {
            ADPF_SOFTWARE_ACTUAL_DURATION_NS
        } else {
            ADPF_TURBO_ACTUAL_DURATION_NS
        }

    private fun createSustainedSession(
        phm: PerformanceHintManager,
        tids: IntArray
    ): PerformanceHintManager.Session? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val created = runCatching {
            phm.createHintSession(tids, ADPF_TARGET_DURATION_NS)
        }.getOrNull() ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { created.setPreferPowerEfficiency(false) }
        }
        runCatching { created.reportActualWorkDuration(currentAdpfActualDurationNs()) }
        return created
    }

    /**
     * Reads `/proc/self/task/<tid>/stat` in a single file open per thread to extract both the
     * thread name `(comm)` and `utime + stime` CPU ticks without opening `/comm` separately.
     */
    private fun readProcessThreadSamples(): List<ThreadSample> {
        val result = ArrayList<ThreadSample>(48)
        runCatching {
            val taskDir = File("/proc/self/task")
            taskDir.listFiles()?.forEach { dir ->
                val tid = dir.name.toIntOrNull() ?: return@forEach
                val statText = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
                val openParen = statText.indexOf('(')
                val closeParen = statText.lastIndexOf(')')
                if (openParen >= 0 && closeParen > openParen && closeParen + 2 < statText.length) {
                    val comm = statText.substring(openParen + 1, closeParen)
                    val fields = statText.substring(closeParen + 2).trim().split(' ')
                    // After "(comm) ", field[11] = utime, field[12] = stime
                    if (fields.size > 12) {
                        val utime = fields[11].toLongOrNull() ?: 0L
                        val stime = fields[12].toLongOrNull() ?: 0L
                        result.add(ThreadSample(tid = tid, comm = comm, ticks = utime + stime))
                    }
                }
            }
        }
        return result
    }

    private fun isIgnoredComm(comm: String, isSoftwareFfmpeg: Boolean): Boolean {
        if (commonIgnoredThreadPrefixes.any { prefix -> comm.startsWith(prefix, ignoreCase = true) }) {
            return true
        }
        return isSoftwareFfmpeg &&
            softwareOnlyIgnoredThreadPrefixes.any { prefix -> comm.startsWith(prefix, ignoreCase = true) }
    }

    companion object {
        private const val EARLY_DISCOVERY_DELAY_MS = 650L
        private const val TICKER_INTERVAL_MS = 8_000L
        private const val PULSE_MIN_INTERVAL_MS = 8_000L
        private const val SESSION_REASSERT_INTERVAL_MS = 24_000L
        // Neutral sustained ADPF hint for software FFmpeg (actual == target): sustains multi-core
        // clocks & resists background demotion WITHOUT over-volting Pass 1 into Pass 2 thermal throttling.
        private const val ADPF_TARGET_DURATION_NS = 16_000_000L
        private const val ADPF_SOFTWARE_ACTUAL_DURATION_NS = 16_000_000L
        private const val ADPF_TURBO_ACTUAL_DURATION_NS = 17_000_000L

        /**
         * Returns a concise hardware topology label (e.g., "8 çekirdek · Prime öncelikli").
         */
        fun describeCpuBoostSummary(
            strings: AppStrings = AppLanguageController.currentStrings()
        ): String {
            val topology = CpuTopology.current()
            val cores = topology.totalCores.coerceAtLeast(1)
            val maxFreqKhz = topology.maxFreqKhz

            return if (maxFreqKhz != null && maxFreqKhz > 0L) {
                val ghz = String.format(java.util.Locale.US, "%.2f GHz", maxFreqKhz / 1_000_000.0)
                strings.cpuBoostWithFreq(ghz, cores)
            } else {
                strings.cpuBoostGeneric(cores)
            }
        }
    }
}
