package eu.stedb.mysyncthing.ui

import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.WALL_CLOCK_WINDOW
import eu.stedb.mysyncthing.core.wallClockAt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/**
 * The alarm that turns "Waiting for devices" red: on the wall clock, never waking the phone,
 * and inside a 10 min window. A plain inexact alarm came 45 min late on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class WallClockAlarmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val at = Instant.parse("2026-11-02T17:03:00Z")

    @Test
    fun aWindowedNonWakingAlarmThatReturnsWhenItFires() = runBlocking {
        var returned = false
        val wait = launch(start = CoroutineStart.UNDISPATCHED) { wallClockAt(app, at); returned = true }
        val alarm = alarms.scheduledAlarms.single()
        assertEquals("wall clock, never waking the phone", AlarmManager.RTC, alarm.getType())
        assertEquals(at.toEpochMilli(), alarm.triggerAtMs)
        assertEquals(10 * 60_000L, WALL_CLOCK_WINDOW.toMillis())
        assertEquals(WALL_CLOCK_WINDOW.toMillis(), alarm.windowLengthMs)
        assertFalse(alarm.isAllowWhileIdle)
        assertFalse(returned)
        alarms.fireAlarm(alarm)
        // The listener runs on the main thread's looper, which this test holds.
        shadowOf(Looper.getMainLooper()).idle()
        wait.join()
        assertTrue(returned)
    }

    /** A new snapshot cancels the wait; its alarm goes with it. */
    @Test
    fun cancelledWaitLeavesNoAlarm() = runBlocking {
        val wait = launch(start = CoroutineStart.UNDISPATCHED) { wallClockAt(app, at) }
        assertEquals(1, alarms.scheduledAlarms.size)
        wait.cancel()
        wait.join()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
