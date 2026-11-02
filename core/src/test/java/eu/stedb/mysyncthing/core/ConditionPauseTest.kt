package eu.stedb.mysyncthing.core

import androidx.datastore.preferences.core.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * The pause the run conditions put on devices: on all of them, or on single devices by their own
 * sync windows. It used to be applied once, when the conditions changed: a device added (or
 * accepted) while on mobile data stayed unpaused and synced over it, and the next start saw the
 * recorded pause and left it so.
 */
class ConditionPauseTest {
    private val blocked = text(R.string.pause_reason_mobile_data)
    private val outside = Hold(raw("outside its windows"))

    /**
     * The core's devices, a fake of its REST calls, with the service's [applyConditions] and the
     * app's [setDevicePausedByUser] run on them as the phone runs them, record in real [Settings].
     */
    private inner class Phone(var devices: List<Device>) : CorePauses {
        val store = FillableStore()
        val settings = Settings(store)
        var reason: UiText? = null
        var own: Set<String> = emptySet()
        val holds: ConditionHolds? get() = runBlocking { settings.conditionHolds() }
        val recorded: Set<String>? get() = holds?.userPaused
        val pause get() = DevicePause(reason, holds, own.associateWith { outside })

        override suspend fun devices() = devices
        override suspend fun setPaused(id: String?, paused: Boolean) {
            devices = devices.map { if (id == null || it.deviceID == id) it.copy(paused = paused) else it }
        }

        fun apply(reason: UiText?, own: Set<String> = emptySet()) = runBlocking {
            this@Phone.reason = reason
            this@Phone.own = own
            assertTrue(applyConditions(this@Phone, settings, reason, own))
        }

        fun userPause(id: String, paused: Boolean) = runBlocking { setDevicePausedByUser(this@Phone, settings, id, paused) }
        fun paused() = devices.map { it.paused }
    }

    private fun dev(id: String, paused: Boolean = false) = Device(deviceID = id, paused = paused)

    @Test
    fun deviceAddedWhileBlockedIsPausedAndResumedLater() {
        val phone = Phone(listOf(dev("A"), dev("B", paused = true)))
        phone.apply(blocked)
        assertEquals(setOf("B"), phone.recorded)
        phone.devices += dev("C")
        phone.apply(blocked)
        assertTrue(phone.devices.all { it.paused })
        assertEquals("the user's own pause is kept", setOf("B"), phone.recorded)
        phone.apply(null)
        assertEquals(listOf(false, true, false), phone.paused())
        assertEquals(null, phone.recorded)
    }

    /** Resume on a device while on mobile data used to be undone at once, and the device stayed paused afterwards. */
    @Test
    fun theUsersChoiceWhileBlockedHoldsAfterwards() {
        val phone = Phone(listOf(dev("A"), dev("B", paused = true)))
        phone.apply(blocked)
        phone.userPause("B", false)
        phone.userPause("A", true)
        assertTrue("still blocked: nothing resumes", phone.devices.all { it.paused })
        assertEquals(setOf("A"), phone.recorded)
        assertTrue(phone.pause.byUser(dev("A")))
        phone.apply(blocked) // an event or a new device while still blocked
        assertTrue(phone.devices.all { it.paused })
        phone.apply(null)
        assertEquals(listOf(true, false), phone.paused())
        assertEquals(null, phone.recorded)
    }

    /**
     * With syncing allowed the pause goes to the core at once, also with the storage full. While
     * blocked a choice that can't be recorded fails, so the screen does not show it as taken.
     */
    @Test
    fun allowedGoesToTheCoreAndAnUnrecordedChoiceFails() {
        val phone = Phone(listOf(dev("A"), dev("B")))
        phone.store.full = true
        phone.userPause("A", true)
        assertEquals(listOf(true, false), phone.paused())
        phone.store.full = false
        phone.apply(blocked)
        phone.store.full = true
        assertThrows(IOException::class.java) { phone.userPause("B", true) }
        assertEquals(setOf("A"), phone.recorded)
    }

    /** A device outside its own windows is held alone; the others sync, and the user's choice for each stands. */
    @Test
    fun ownWindowsHoldOnlyTheirDevice() {
        val phone = Phone(listOf(dev("A"), dev("B"), dev("C", paused = true)))
        phone.apply(null, own = setOf("B", "C"))
        assertEquals(listOf(false, true, true), phone.paused())
        assertEquals(ConditionHolds(setOf("B", "C"), setOf("C")), phone.holds)
        assertTrue(phone.pause.blocked(dev("B", paused = true)))
        assertFalse(phone.pause.blocked(dev("A")))
        // Pausing A, not held, goes to the core; resuming C, held, waits for its windows.
        phone.userPause("A", true)
        phone.userPause("C", false)
        assertEquals(listOf(true, true, true), phone.paused())
        // B's window opens; the global block comes and goes over all of them.
        phone.apply(null, own = setOf("C"))
        assertEquals(listOf(true, false, true), phone.paused())
        phone.apply(blocked, own = setOf("C"))
        assertTrue(phone.devices.all { it.paused })
        assertEquals(ConditionHolds(setOf("A", "B", "C"), setOf("A")), phone.holds)
        phone.apply(null, own = setOf("C"))
        assertEquals(listOf(true, false, true), phone.paused())
        phone.apply(null)
        assertEquals("C as the user left it", listOf(true, false, false), phone.paused())
        assertNull(phone.holds)
    }

    /** Resumed in the web GUI while its windows hold it: paused again, and the user's choice kept. */
    @Test
    fun aHeldDeviceResumedElsewhereIsPausedAgain() {
        val phone = Phone(listOf(dev("A")))
        phone.apply(null, own = setOf("A"))
        phone.devices = listOf(dev("A"))
        phone.apply(null, own = setOf("A"))
        assertEquals(listOf(true), phone.paused())
        assertEquals(ConditionHolds(setOf("A"), emptySet()), phone.holds)
    }

    /** A record from before own windows held every device; it still lets go of them all, and is written out when kept. */
    @Test
    fun aRecordFromBeforeOwnWindows() {
        val phone = Phone(listOf(dev("A", paused = true), dev("B", paused = true)))
        runBlocking { phone.store.edit { it[Settings.CONDITION_PAUSE] = setOf("B") } }
        assertEquals(ConditionHolds(null, setOf("B")), phone.holds)
        assertTrue(phone.pause.byUser(dev("B", paused = true)))
        assertFalse(phone.pause.byUser(dev("A", paused = true)))
        phone.apply(blocked)
        assertEquals(ConditionHolds(setOf("A", "B"), setOf("B")), phone.holds)
        phone.apply(null)
        assertEquals(listOf(false, true), phone.paused())
        assertNull(phone.holds)

        val old = Phone(listOf(dev("A", paused = true)))
        runBlocking { old.store.edit { it[Settings.CONDITION_PAUSE] = emptySet() } }
        old.apply(null)
        assertEquals(listOf(false), old.paused())
        assertNull(old.holds)
    }

    @Test
    fun nothingToDoWhenInLine() {
        assertEquals(ConditionPause(), conditionPause(false, emptySet(), null, listOf(dev("A"), dev("B", paused = true))))
        val both = ConditionHolds(setOf("A", "B"), setOf("B"))
        assertEquals(ConditionPause(), conditionPause(true, emptySet(), both, listOf(dev("A", paused = true), dev("B", paused = true))))
        assertEquals(ConditionPause(), conditionPause(false, setOf("A", "B"), both, listOf(dev("A", paused = true), dev("B", paused = true))))
        // A start with a record but nothing unpaused, on a network that is fine now.
        assertEquals(
            ConditionPause(resume = listOf("A"), release = true),
            conditionPause(false, emptySet(), ConditionHolds(setOf("A"), emptySet()), listOf(dev("A", paused = true))),
        )
        // A removed device leaves the record.
        assertEquals(
            ConditionPause(release = true, released = ConditionHolds(setOf("A"), emptySet())),
            conditionPause(false, setOf("A"), ConditionHolds(setOf("A", "gone"), setOf("gone")), listOf(dev("A", paused = true))),
        )
    }

    /**
     * Any run of blocks, own windows, new devices and the user's own pauses: after each step the
     * held devices are paused, every other device is as the user left it, and the screens show
     * the user's choice; at the end nothing is held and only the user's own pauses remain.
     */
    @Test
    fun anyRunEndsWithTheUsersOwnPausesOnly() {
        val r = Random(20261102)
        repeat(3_000) {
            val ids = List(r.nextInt(0, 6)) { "D$it" }
            val phone = Phone(ids.map { dev(it, paused = r.nextBoolean()) })
            val userPaused = phone.devices.filter { it.paused }.map { it.deviceID }.toMutableSet()
            var next = ids.size
            repeat(r.nextInt(1, 14)) {
                val reason = if (r.nextInt(3) == 0) blocked else null
                val own = phone.devices.map { it.deviceID }.filter { r.nextInt(3) == 0 }.toSet()
                when (r.nextInt(5)) {
                    0 -> phone.devices += dev("D${next++}") // added in the app or the web GUI
                    // The user pauses or resumes a device, also while the run conditions hold it.
                    1, 2 -> if (phone.devices.isNotEmpty()) {
                        val d = phone.devices.random(r).deviceID
                        val paused = d !in userPaused
                        phone.userPause(d, paused)
                        if (paused) userPaused += d else userPaused -= d
                    }
                    3 -> if (phone.devices.isNotEmpty()) { // removed
                        val d = phone.devices.random(r).deviceID
                        phone.devices = phone.devices.filter { it.deviceID != d }
                        userPaused -= d
                    }
                }
                assertEquals("the screens show the user's own choice", userPaused, phone.devices.filter { phone.pause.byUser(it) }.map { it.deviceID }.toSet())
                phone.apply(reason, own)
                for (d in phone.devices) {
                    val held = reason != null || d.deviceID in own
                    assertEquals("$d held=$held", held || d.deviceID in userPaused, d.paused)
                    assertEquals(held, phone.pause.blocked(d))
                }
                assertEquals("the screens show the user's own choice", userPaused, phone.devices.filter { phone.pause.byUser(it) }.map { it.deviceID }.toSet())
            }
            phone.apply(null)
            assertEquals(userPaused, phone.devices.filter { it.paused }.map { it.deviceID }.toSet())
            assertEquals(null, phone.holds)
        }
    }
}
