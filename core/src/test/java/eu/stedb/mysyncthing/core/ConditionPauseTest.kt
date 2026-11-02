package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * The pause the run conditions put on devices. It used to be applied once, when
 * the conditions changed: a device added (or accepted) while on mobile data stayed
 * unpaused and synced over it, and the next start saw the recorded pause and left
 * it so.
 */
class ConditionPauseTest {
    private val blocked = text(R.string.pause_reason_mobile_data)

    /**
     * The core's devices, a fake of its REST calls, with the service's [applyConditions] and the
     * app's [setDevicePausedByUser] run on them as the phone runs them, record in real [Settings].
     */
    private class Phone(var devices: List<Device>) : CorePauses {
        val store = FillableStore()
        val settings = Settings(store)
        var reason: UiText? = null
        val recorded: Set<String>? get() = runBlocking { settings.conditionPause() }
        val pause get() = DevicePause(reason, recorded)

        override suspend fun devices() = devices
        override suspend fun setPaused(id: String?, paused: Boolean) {
            devices = devices.map { if (id == null || it.deviceID == id) it.copy(paused = paused) else it }
        }

        fun apply(reason: UiText?) = runBlocking {
            this@Phone.reason = reason
            assertTrue(applyConditions(this@Phone, settings, reason))
        }

        fun userPause(id: String, paused: Boolean) = runBlocking { setDevicePausedByUser(this@Phone, settings, id, paused) }
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
        assertEquals(listOf(false, true, false), phone.devices.map { it.paused })
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
        assertEquals(listOf(true, false), phone.devices.map { it.paused })
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
        assertEquals(listOf(true, false), phone.devices.map { it.paused })
        phone.store.full = false
        phone.apply(blocked)
        phone.store.full = true
        assertThrows(IOException::class.java) { phone.userPause("B", true) }
        assertEquals(setOf("A"), phone.recorded)
    }

    @Test
    fun nothingToDoWhenInLine() {
        assertEquals(ConditionPause(), conditionPause(null, null, listOf(dev("A"), dev("B", paused = true))))
        assertEquals(ConditionPause(), conditionPause(blocked, setOf("B"), listOf(dev("A", paused = true), dev("B", paused = true))))
        // A start with a record but nothing unpaused, on a network that is fine now.
        assertEquals(ConditionPause(resume = listOf("A"), clearRecord = true), conditionPause(null, emptySet(), listOf(dev("A", paused = true))))
    }

    @Test
    fun anyRunEndsWithTheUsersOwnPausesOnly() {
        val r = Random(20261029)
        repeat(2_000) {
            val ids = List(r.nextInt(0, 6)) { "D$it" }
            val phone = Phone(ids.map { dev(it, paused = r.nextBoolean()) })
            val userPaused = phone.devices.filter { it.paused }.map { it.deviceID }.toMutableSet()
            var next = ids.size
            repeat(r.nextInt(1, 12)) {
                val reason = if (r.nextBoolean()) blocked else null
                when (r.nextInt(4)) {
                    0 -> phone.devices += dev("D${next++}") // added in the app or the web GUI
                    // The user pauses or resumes a device, also while the run conditions pause everything.
                    1 -> if (phone.devices.isNotEmpty()) {
                        val d = phone.devices.random(r).deviceID
                        val paused = d !in userPaused
                        phone.userPause(d, paused)
                        if (paused) userPaused += d else userPaused -= d
                    }
                }
                assertEquals("the screens show the user's own choice", userPaused, phone.devices.filter { phone.pause.byUser(it) }.map { it.deviceID }.toSet())
                phone.apply(reason)
                if (reason != null) assertTrue("blocked: every device paused", phone.devices.all { it.paused })
                assertEquals("the screens show the user's own choice", userPaused, phone.devices.filter { phone.pause.byUser(it) }.map { it.deviceID }.toSet())
            }
            phone.apply(null)
            assertEquals(userPaused, phone.devices.filter { it.paused }.map { it.deviceID }.toSet())
            assertEquals(null, phone.recorded)
        }
    }
}
