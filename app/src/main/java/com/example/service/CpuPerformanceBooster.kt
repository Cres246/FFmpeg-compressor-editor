package com.example.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.io.File

/**
 * Engages maximum CPU throughput for native FFmpeg / libx264 / libx265 encoding threads:
 * 1. Inspects `/proc/self/task` (sandbox-local, zero permissions) to discover active native
 *    FFmpeg/encoder worker threads and measure their CPU tick deltas.
 * 2. Assigns the highest allowed Linux nice priority (`THREAD_PRIORITY_URGENT_DISPLAY` / `-10`)
 *    specifically to the single heaviest worker thread (encoder/lookahead coordinator) so the
 *    Qualcomm WALT / EAS scheduler places the biggest load straight onto the Prime core
 *    (e.g. Snapdragon 7 Gen 3 Cortex-A715 @ 2.63 GHz), while keeping secondary encoder workers
 *    at elevated priority (`THREAD_PRIORITY_DISPLAY`) across all remaining Big + Little cores.
 * 3. Registers all active encoder TIDs into Android's ADPF [PerformanceHintManager.Session]
 *    (updating dynamically via `setThreads()` on Android 14+/16) and reports aggressive work
 *    duration hints so PowerHAL keeps Prime + Performance core frequencies boosted.
 */
class CpuPerformanceBooster(private val context: Context) {

    private var perfHintSession: PerformanceHintManager.Session? = null
    private var lastRegisteredTids: IntArray = IntArray(0)
    private var lastSampledCpuTicks: Map<Int, Long> = emptyMap()
    private var lastBoostPulseMs: Long = 0L

    private val ignoredThreadPrefixes = listOf(
        "Binder:", "RenderThread", "HeapTaskDaemon", "ReferenceQueue",
        "FinalizerDaemon", "FinalizerWatch", "Profile Saver", "Jit thread",
        "Signal Catcher", "perfetto", "hwuiTask", "mali-", "adreno",
        "ConnectivityThr", "OkHttp", "DefaultDispatch"
    )

    fun start() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        lastSampledCpuTicks = readProcessThreadTicks()
        refreshAndPulse(force = true)
    }

    fun pulseIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastBoostPulseMs >= 1800L) {
            refreshAndPulse(force = false)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { perfHintSession?.close() }
        }
        perfHintSession = null
        lastRegisteredTids = IntArray(0)
        lastSampledCpuTicks = emptyMap()
    }

    private fun refreshAndPulse(force: Boolean) {
        lastBoostPulseMs = System.currentTimeMillis()
        val currentTicks = readProcessThreadTicks()
        val mainPid = Process.myPid()

        // Rank threads by CPU delta since last sample (falling back to total ticks on first sample)
        val rankedEncoderTids = currentTicks.entries
            .filter { (tid, _) -> tid != mainPid && !isSystemOrUiThread(tid) }
            .map { (tid, ticks) ->
                val prev = lastSampledCpuTicks[tid] ?: 0L
                val delta = (ticks - prev).coerceAtLeast(0L)
                Triple(tid, delta, ticks)
            }
            .sortedWith(compareByDescending<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .take(16)

        lastSampledCpuTicks = currentTicks

        val targetTids = if (rankedEncoderTids.isNotEmpty()) {
            rankedEncoderTids.toIntArray()
        } else {
            intArrayOf(Process.myTid())
        }

        // Priority tiering so the kernel places the heaviest thread on the Prime core first,
        // while keeping the full multi-core pool boosted across Big + Little cores.
        targetTids.forEachIndexed { index, tid ->
            if (index == 0) {
                setHighestAllowedPriority(tid, preferredNice = -10, fallbackNice = Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } else if (index <= 3) {
                setHighestAllowedPriority(tid, preferredNice = Process.THREAD_PRIORITY_URGENT_DISPLAY, fallbackNice = Process.THREAD_PRIORITY_DISPLAY)
            } else {
                setHighestAllowedPriority(tid, preferredNice = Process.THREAD_PRIORITY_DISPLAY, fallbackNice = Process.THREAD_PRIORITY_MORE_FAVORABLE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateAdpfSession(targetTids, force)
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

    private fun updateAdpfSession(tids: IntArray, force: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val tidsChanged = !tids.contentEquals(lastRegisteredTids)

        runCatching {
            val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
            val existing = perfHintSession

            if (existing == null) {
                // Tight 12ms target with 24ms actual duration signals max boost (Prime + Big cores)
                val created = phm.createHintSession(tids, 12_000_000L)
                if (created != null) {
                    perfHintSession = created
                    lastRegisteredTids = tids.copyOf()
                    created.reportActualWorkDuration(24_000_000L)
                }
            } else {
                if (tidsChanged || force) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val updated = runCatching {
                            existing.setThreads(tids)
                            true
                        }.getOrDefault(false)
                        if (updated) {
                            lastRegisteredTids = tids.copyOf()
                        } else {
                            runCatching { existing.close() }
                            perfHintSession = phm.createHintSession(tids, 12_000_000L)
                            lastRegisteredTids = tids.copyOf()
                        }
                    } else {
                        runCatching { existing.close() }
                        perfHintSession = phm.createHintSession(tids, 12_000_000L)
                        lastRegisteredTids = tids.copyOf()
                    }
                }
                perfHintSession?.reportActualWorkDuration(24_000_000L)
            }
        }
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
        /**
         * Returns a concise hardware topology label (e.g., "8 çekirdek · Prime öncelikli").
         */
        fun describeCpuBoostSummary(): String {
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
                "Prime ($ghz) öncelikli · $cores çekirdek tam güç"
            } else {
                "Prime çekirdek öncelikli · $cores çekirdek tam güç"
            }
        }
    }
}
