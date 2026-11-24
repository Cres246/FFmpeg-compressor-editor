package com.example.service

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundExecutionPolicyTest {

    @Test
    fun android15PlusPrioritizesMediaProcessingAndMediaPlayback() {
        val types35 = BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        val types36 = BackgroundExecutionPolicy.resolveForegroundServiceTypes(36)

        val expected = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        assertEquals(expected, types35)
        assertEquals(expected, types36)
    }

    @Test
    fun android10To14UsesMediaPlaybackAndDataSync() {
        val types29 = BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION_CODES.Q)
        val types34 = BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        val expected = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        assertEquals(expected, types29)
        assertEquals(expected, types34)
    }

    @Test
    fun preAndroid10UsesLegacyTwoArgStartForeground() {
        assertTrue(BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION_CODES.P).isEmpty())
        assertTrue(BackgroundExecutionPolicy.resolveForegroundServiceTypes(Build.VERSION_CODES.N).isEmpty())
    }

    @Test
    fun onTimeoutTransitionsTimeLimitedTypesToMediaPlayback() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            BackgroundExecutionPolicy.resolveTimeoutFallbackType(
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                timedOutFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            BackgroundExecutionPolicy.resolveTimeoutFallbackType(
                sdkInt = 36,
                timedOutFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )
        assertNull(
            BackgroundExecutionPolicy.resolveTimeoutFallbackType(
                sdkInt = 36,
                timedOutFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        )
        assertNull(
            BackgroundExecutionPolicy.resolveTimeoutFallbackType(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                timedOutFgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )
    }

    @Test
    fun slidingWakeLockRenewsOnDropOrIntervalAndStaysUnderHonor60MinLimit() {
        assertTrue(BackgroundExecutionPolicy.WAKELOCK_LEASE_MS < 60L * 60L * 1000L)
        assertTrue(BackgroundExecutionPolicy.WAKELOCK_RENEW_INTERVAL_MS < BackgroundExecutionPolicy.WAKELOCK_LEASE_MS)

        // Dropped lock must renew immediately even 1s after acquisition
        assertTrue(
            BackgroundExecutionPolicy.shouldRenewWakeLock(
                lastAcquiredElapsedMs = 10_000L,
                nowElapsedMs = 11_000L,
                isHeld = false
            )
        )
        // Held lock within 5 minutes does not churn
        assertFalse(
            BackgroundExecutionPolicy.shouldRenewWakeLock(
                lastAcquiredElapsedMs = 10_000L,
                nowElapsedMs = 10_000L + 4L * 60L * 1000L,
                isHeld = true
            )
        )
        // Held lock at/past 5-minute sliding renewal mark renews smoothly
        assertTrue(
            BackgroundExecutionPolicy.shouldRenewWakeLock(
                lastAcquiredElapsedMs = 10_000L,
                nowElapsedMs = 10_000L + BackgroundExecutionPolicy.WAKELOCK_RENEW_INTERVAL_MS,
                isHeld = true
            )
        )
    }

    @Test
    fun silentNotificationRefreshTriggersAfterCadenceWindow() {
        assertTrue(BackgroundExecutionPolicy.shouldRefreshNotification(0L, 1_000L))
        assertFalse(BackgroundExecutionPolicy.shouldRefreshNotification(10_000L, 20_000L))
        assertTrue(
            BackgroundExecutionPolicy.shouldRefreshNotification(
                10_000L,
                10_000L + BackgroundExecutionPolicy.SILENT_NOTIFY_REFRESH_MS
            )
        )
    }
}
