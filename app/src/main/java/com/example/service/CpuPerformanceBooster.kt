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
 * Mode-aware CPU scheduling & thermal guard for both software FFmpeg (`libx264`/`libx265`)
 * and hardware Turbo (`Media3`/`DirectMediaCodec`):
 *
 * - **[BoostMode.FFMPEG_SOFTWARE] (Termux-Parity Neutral Profile):**
 *   Keeps all CPU cores unlocked (NO `sched_setaffinity`), opens NO ADPF `PerformanceHintManager`
 *   session (avoiding `uclamp.min` over-volting and premature MagicOS/Snapdragon thermal clamping
 *   by Pass 2), and leaves all encoder threads at Linux default `nice 0` (`THREAD_PRIORITY_DEFAULT`)
 *   just like native Termux. A lightweight 15s guard inspects thread priorities without parsing
 *   `/proc/<tid>/stat` and only restores a worker thread back to `0` if an OEM background
 *   scheduler demotes it to `> 0` (e.g., `+10` `THREAD_PRIORITY_BACKGROUND`).
 *
 * - **[BoostMode.TURBO_MEDIACODEC] (Low-Latency Hardware VPU Feeder Profile):**
 *   Hardware MediaCodec/Surface transcoding uses bursty feeder threads rather than full-CPU
 *   saturation, so it keeps `URGENT_DISPLAY`/`DISPLAY` priorities and a balanced ADPF hint
 *   session (`setPreferPowerEfficiency(false)`, `16ms/17ms`) to prevent VPU pipeline stalls.
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
    private var allocatorEncodingModeActive: Boolean = false

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
            tickerJob?.cancel()
            tickerJob = null
            earlyDiscoveryJob?.cancel()
            earlyDiscoveryJob = null
            boostedPriorityTids.clear()
            hasPreviousCpuSample = false
            lastSampledCpuTicks = emptyMap()
            lastSessionReassertElapsedMs = 0L

            if (mode == BoostMode.FFMPEG_SOFTWARE) {
                // Close any lingering Turbo ADPF session so PowerHAL drops uclamp overrides
                // immediately and Snapdragon/MagicOS schedules FFmpeg neutrally like Termux.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { perfHintSession?.close() }
                }
                perfHintSession = null
                lastRegisteredTids = IntArray(0)
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
                if (!allocatorEncodingModeActive) {
                    NativeCpuAffinity.setAllocatorEncodingMode(true)
                    allocatorEncodingModeActive = true
                }

                tickerJob = scope?.launch(Dispatchers.Default) {
                    while (isActive && active) {
                        delay(SOFTWARE_GUARD_INTERVAL_MS)
                        if (!active) break
                        synchronized(lock) {
                            if (!active || currentMode != BoostMode.FFMPEG_SOFTWARE) return@synchronized
                            guardAgainstBackgroundDemotionLocked()
                        }
                    }
                }
                return
            }

            // TURBO_MEDIACODEC: release any cached software-FFmpeg pages before MediaCodec init
            if (allocatorEncodingModeActive) {
                allocatorEncodingModeActive = false
                NativeCpuAffinity.setAllocatorEncodingMode(false)
            }
            // Low-latency feeder priority + balanced ADPF hint session
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            lastSampledCpuTicks = readProcessThreadSamples().associate { it.tid to it.ticks }
            refreshTurboLocked(force = true)

            tickerJob = scope?.launch(Dispatchers.Default) {
                while (isActive && active) {
                    delay(TURBO_TICKER_INTERVAL_MS)
                    if (!active) break
                    synchronized(lock) {
                        if (!active || currentMode != BoostMode.TURBO_MEDIACODEC) return@synchronized
                        val now = SystemClock.elapsedRealtime()
                        val shouldForceSession = (now - lastSessionReassertElapsedMs) >= SESSION_REASSERT_INTERVAL_MS
                        refreshTurboLocked(force = shouldForceSession)
                    }
                }
            }
        }
    }

    /**
     * Triggers a one-shot thread scan shortly after a Turbo pipeline starts (~650ms).
     * Intentionally a no-op for [BoostMode.FFMPEG_SOFTWARE], which runs at neutral `nice 0`
     * without ADPF to match native Termux thermal/scheduler behavior.
     */
    fun scheduleEarlyThreadDiscovery(scope: CoroutineScope) {
        synchronized(lock) {
            if (!active || currentMode != BoostMode.TURBO_MEDIACODEC) return
            earlyDiscoveryJob?.cancel()
            earlyDiscoveryJob = scope.launch(Dispatchers.Default) {
                delay(EARLY_DISCOVERY_DELAY_MS)
                synchronized(lock) {
                    if (!active || currentMode != BoostMode.TURBO_MEDIACODEC) return@synchronized
                    refreshTurboLocked(force = true)
                }
            }
        }
    }

    fun pulseIfDue() {
        if (!active || currentMode != BoostMode.TURBO_MEDIACODEC) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBoostPulseElapsedMs >= TURBO_PULSE_MIN_INTERVAL_MS) {
            synchronized(lock) {
                if (!active || currentMode != BoostMode.TURBO_MEDIACODEC) return
                val checkNow = SystemClock.elapsedRealtime()
                if (checkNow - lastBoostPulseElapsedMs >= TURBO_PULSE_MIN_INTERVAL_MS) {
                    val shouldForceSession = (checkNow - lastSessionReassertElapsedMs) >= SESSION_REASSERT_INTERVAL_MS
                    refreshTurboLocked(force = shouldForceSession)
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
            if (allocatorEncodingModeActive) {
                allocatorEncodingModeActive = false
                NativeCpuAffinity.setAllocatorEncodingMode(false)
            }
        }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
    }

    /**
     * Ultra-lightweight Termux-parity background guard for software FFmpeg:
     * In foreground (all worker threads already at `nice <= 0`), does zero `/proc/<tid>/stat`
     * reads and zero `setThreadPriority` syscalls. Only if an OEM scheduler demotes a worker
     * thread to `> 0` (e.g., `+10` `THREAD_PRIORITY_BACKGROUND`) does it lazily check `comm`
     * and restore the thread cleanly back to neutral `0` (`THREAD_PRIORITY_DEFAULT`).
     */
    private fun guardAgainstBackgroundDemotionLocked() {
        if (!active) return
        val mainPid = Process.myPid()
        runCatching {
            val tidNames = File("/proc/self/task").list() ?: return
            for (tidName in tidNames) {
                val tid = tidName.toIntOrNull() ?: continue
                if (tid == mainPid) continue
                val currentNice = runCatching { Process.getThreadPriority(tid) }.getOrNull() ?: continue
                if (currentNice > Process.THREAD_PRIORITY_DEFAULT) {
                    val comm = runCatching { File("/proc/self/task/$tid/comm").readText().trim() }
                        .getOrNull()
                        .orEmpty()
                    if (!isIgnoredComm(comm, isSoftwareFfmpeg = true)) {
                        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT) }
                    }
                }
            }
        }
    }

    private fun refreshTurboLocked(force: Boolean) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        lastBoostPulseElapsedMs = now
        val samples = readProcessThreadSamples()
        val mainPid = Process.myPid()
        val maxThreads = 16

        val candidates = samples
            .filter { sample -> sample.tid != mainPid && !isIgnoredComm(sample.comm, isSoftwareFfmpeg = false) }
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

        val rankedFeederTids = activeCandidates
            .sortedWith(compareByDescending<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .take(maxThreads)

        lastSampledCpuTicks = samples.associate { it.tid to it.ticks }
        hasPreviousCpuSample = true

        val targetTids = if (rankedFeederTids.isNotEmpty()) {
            rankedFeederTids.toIntArray()
        } else {
            intArrayOf(Process.myTid())
        }

        if (force) {
            boostedPriorityTids.clear()
        }
        targetTids.forEachIndexed { index, tid ->
            if (force || boostedPriorityTids.add(tid)) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateTurboAdpfSession(targetTids, force, now)
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

    private fun updateTurboAdpfSession(tids: IntArray, force: Boolean, nowElapsedMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val adpfTids = if (tids.size > 16) tids.copyOf(16) else tids
        val tidsChanged = !adpfTids.contentEquals(lastRegisteredTids)

        runCatching {
            val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
            var session = perfHintSession

            if (session == null) {
                session = createTurboSession(phm, adpfTids)
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
                            session = createTurboSession(phm, adpfTids)
                            perfHintSession = session
                            lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                            lastSessionReassertElapsedMs = nowElapsedMs
                        }
                    } else {
                        runCatching { session.close() }
                        session = createTurboSession(phm, adpfTids)
                        perfHintSession = session
                        lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                        lastSessionReassertElapsedMs = nowElapsedMs
                    }
                }
                val reported = runCatching {
                    session?.reportActualWorkDuration(ADPF_TURBO_ACTUAL_DURATION_NS)
                    true
                }.getOrDefault(false)
                if (!reported) {
                    runCatching { session?.close() }
                    val recreated = createTurboSession(phm, adpfTids)
                    perfHintSession = recreated
                    lastRegisteredTids = if (recreated != null) adpfTids.copyOf() else IntArray(0)
                    lastSessionReassertElapsedMs = nowElapsedMs
                }
            }
        }
    }

    private fun createTurboSession(
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
        runCatching { created.reportActualWorkDuration(ADPF_TURBO_ACTUAL_DURATION_NS) }
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
        private const val SOFTWARE_GUARD_INTERVAL_MS = 15_000L
        private const val EARLY_DISCOVERY_DELAY_MS = 650L
        private const val TURBO_TICKER_INTERVAL_MS = 8_000L
        private const val TURBO_PULSE_MIN_INTERVAL_MS = 8_000L
        private const val SESSION_REASSERT_INTERVAL_MS = 24_000L
        private const val ADPF_TARGET_DURATION_NS = 16_000_000L
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
