package com.example.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import com.example.domain.AppLanguageController
import com.example.domain.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Engages sustained maximum CPU/ADPF throughput for both software FFmpeg (`libx264`/`libx265`)
 * and hardware Turbo (`Media3`/`DirectMediaCodec`) threads, both in the foreground and while
 * backgrounded:
 * 1. Inspects `/proc/self/task` (sandbox-local, zero permissions) to discover active native
 *    FFmpeg, MediaCodec, GL, and demuxer worker threads and measure their CPU tick deltas.
 * 2. Continuously re-applies the highest allowed Linux nice priority (`-10` on top 2 workers,
 *    `THREAD_PRIORITY_URGENT_DISPLAY` on next 6, `THREAD_PRIORITY_DISPLAY` across remaining pool)
 *    every 1.2s via a dedicated ticker so OEM schedulers (Honor MagicOS `iAware` / Qualcomm WALT)
 *    cannot silently demote background encoding threads onto efficiency Little cores.
 * 3. Registers active encoder TIDs into Android's ADPF [PerformanceHintManager.Session], sets
 *    `setPreferPowerEfficiency(false)` on Android 15+ (API 35+), updates threads via `setThreads()`
 *    on Android 14+, and pulses aggressive work-duration overruns (`8ms` target / `24ms` actual)
 *    so PowerHAL sustains Prime + Performance core clocks even when no Activity is visible.
 */
class CpuPerformanceBooster(private val context: Context) {

    enum class BoostMode {
        FFMPEG_SOFTWARE,
        TURBO_MEDIACODEC
    }

    private var perfHintSession: PerformanceHintManager.Session? = null
    private var lastRegisteredTids: IntArray = IntArray(0)
    private var lastSampledCpuTicks: Map<Int, Long> = emptyMap()
    private var lastBoostPulseElapsedMs: Long = 0L
    private var lastSessionReassertElapsedMs: Long = 0L
    private var tickerJob: Job? = null

    private val ignoredThreadPrefixes = listOf(
        "Binder:", "RenderThread", "HeapTaskDaemon", "ReferenceQueue",
        "FinalizerDaemon", "FinalizerWatch", "Profile Saver", "Jit thread",
        "Signal Catcher", "perfetto", "hwuiTask", "ConnectivityThr", "OkHttp"
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
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            lastSampledCpuTicks = readProcessThreadTicks()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { perfHintSession?.close() }
            }
            perfHintSession = null
            lastRegisteredTids = IntArray(0)
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
        val currentTicks = readProcessThreadTicks()
        val mainPid = Process.myPid()
        val maxThreads = if (currentMode == BoostMode.FFMPEG_SOFTWARE) 32 else 16

        // Rank worker threads by CPU delta since last sample (falling back to total ticks on first sample)
        val rankedEncoderTids = currentTicks.entries
            .filter { (tid, _) -> tid != mainPid && !isSystemOrUiThread(tid) }
            .map { (tid, ticks) ->
                val prev = lastSampledCpuTicks[tid] ?: 0L
                val delta = (ticks - prev).coerceAtLeast(0L)
                Triple(tid, delta, ticks)
            }
            .sortedWith(compareByDescending<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .take(maxThreads)

        lastSampledCpuTicks = currentTicks

        val targetTids = if (rankedEncoderTids.isNotEmpty()) {
            rankedEncoderTids.toIntArray()
        } else {
            intArrayOf(Process.myTid())
        }

        // Priority tiering so the kernel places the heaviest threads on Prime + Big cores first,
        // while keeping the full multi-core pool boosted and preventing OEM background demotion.
        val isSoftwareFfmpeg = currentMode == BoostMode.FFMPEG_SOFTWARE
        targetTids.forEachIndexed { index, tid ->
            if (isSoftwareFfmpeg) {
                when {
                    index <= 1 -> setHighestAllowedPriority(
                        tid,
                        preferredNice = -10,
                        fallbackNice = Process.THREAD_PRIORITY_URGENT_DISPLAY
                    )
                    index <= 7 -> setHighestAllowedPriority(
                        tid,
                        preferredNice = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                        fallbackNice = Process.THREAD_PRIORITY_DISPLAY
                    )
                    else -> setHighestAllowedPriority(
                        tid,
                        preferredNice = Process.THREAD_PRIORITY_DISPLAY,
                        fallbackNice = Process.THREAD_PRIORITY_MORE_FAVORABLE
                    )
                }
            } else {
                // Turbo MediaCodec: keep Media3/GL/MediaCodec feeder threads unthrottled in background
                // without starving hardware VPU/AudioFlinger Binder IPC threads.
                when {
                    index <= 3 -> setHighestAllowedPriority(
                        tid,
                        preferredNice = Process.THREAD_PRIORITY_URGENT_DISPLAY,
                        fallbackNice = Process.THREAD_PRIORITY_DISPLAY
                    )
                    else -> setHighestAllowedPriority(
                        tid,
                        preferredNice = Process.THREAD_PRIORITY_DISPLAY,
                        fallbackNice = Process.THREAD_PRIORITY_MORE_FAVORABLE
                    )
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
                session = createFullThrottleSession(phm, adpfTids)
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
                            session = createFullThrottleSession(phm, adpfTids)
                            perfHintSession = session
                            lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                            lastSessionReassertElapsedMs = nowElapsedMs
                        }
                    } else {
                        runCatching { session.close() }
                        session = createFullThrottleSession(phm, adpfTids)
                        perfHintSession = session
                        lastRegisteredTids = if (session != null) adpfTids.copyOf() else IntArray(0)
                        lastSessionReassertElapsedMs = nowElapsedMs
                    }
                }
                val reported = runCatching {
                    session?.reportActualWorkDuration(ADPF_ACTUAL_DURATION_NS)
                    true
                }.getOrDefault(false)
                if (!reported) {
                    // Recover cleanly if OS invalidated the ADPF session during fg -> bg transition
                    runCatching { session?.close() }
                    val recreated = createFullThrottleSession(phm, adpfTids)
                    perfHintSession = recreated
                    lastRegisteredTids = if (recreated != null) adpfTids.copyOf() else IntArray(0)
                    lastSessionReassertElapsedMs = nowElapsedMs
                }
            }
        }
    }

    private fun createFullThrottleSession(
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
        runCatching { created.reportActualWorkDuration(ADPF_ACTUAL_DURATION_NS) }
        return created
    }

    private fun readProcessThreadTicks(): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        runCatching {
            val taskDir = File("/proc/self/task")
            taskDir.listFiles()?.forEach { dir ->
                val tid = dir.name.toIntOrNull() ?: return@forEach
                val statText = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
                val closeParen = statText.lastIndexOf(')')
                if (closeParen > 0 && closeParen + 2 < statText.length) {
                    val fields = statText.substring(closeParen + 2).trim().split(' ')
                    // After "(comm) ", field[11] = utime, field[12] = stime
                    if (fields.size > 12) {
                        val utime = fields[11].toLongOrNull() ?: 0L
                        val stime = fields[12].toLongOrNull() ?: 0L
                        result[tid] = utime + stime
                    }
                }
            }
        }
        return result
    }

    private fun isSystemOrUiThread(tid: Int): Boolean {
        val comm = runCatching { File("/proc/self/task/$tid/comm").readText().trim() }.getOrNull()
            ?: return false
        return ignoredThreadPrefixes.any { prefix -> comm.startsWith(prefix, ignoreCase = true) }
    }

    companion object {
        private const val TICKER_INTERVAL_MS = 1_200L
        private const val PULSE_MIN_INTERVAL_MS = 1_200L
        private const val SESSION_REASSERT_INTERVAL_MS = 12_000L
        // Tight 8ms target with 24ms actual overrun drives Prime + Big cores at full turbo clocks
        private const val ADPF_TARGET_DURATION_NS = 8_000_000L
        private const val ADPF_ACTUAL_DURATION_NS = 24_000_000L

        /**
         * Returns a concise hardware topology label (e.g., "8 çekirdek · Prime öncelikli").
         */
        fun describeCpuBoostSummary(
            strings: AppStrings = AppLanguageController.currentStrings()
        ): String {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val maxFreqKhz = runCatching {
                (0 until cores).mapNotNull { idx ->
                    File("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
                        .takeIf { it.canRead() }
                        ?.readText()
                        ?.trim()
                        ?.toLongOrNull()
                }.maxOrNull()
            }.getOrNull()

            return if (maxFreqKhz != null && maxFreqKhz > 0L) {
                val ghz = String.format(java.util.Locale.US, "%.2f GHz", maxFreqKhz / 1_000_000.0)
                strings.cpuBoostWithFreq(ghz, cores)
            } else {
                strings.cpuBoostGeneric(cores)
            }
        }
    }
}
