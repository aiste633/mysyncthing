package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pause the run conditions put on devices. It used to be applied once, when
 * the conditions changed: a device added (or accepted) while on mobile data stayed
 * unpaused and synced over it, and the next start saw the recorded pause and left
 * it so.
 */
class ConditionPauseTest {
    private val blocked = text(R.string.pause_reason_mobile_data)

    /** The core's devices after the service carried out [conditionPause], and the record it keeps. */
    private class Phone(var devices: List<Device>, var recorded: Set<String>? = null) {
        fun apply(reason: UiText?) {
            val plan = conditionPause(reason, recorded, devices)
            plan.record?.let { recorded = it }
            if (plan.pauseAll) devices = devices.map { it.copy(paused = true) }
            devices = devices.map { if (it.deviceID in plan.resume) it.copy(paused = false) else it }
            if (plan.clearRecord) recorded = null
        }
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
                    1 -> if (phone.recorded == null && phone.devices.isNotEmpty()) {
                        // The user pauses or resumes a device while syncing is allowed.
                        val d = phone.devices.random(r)
                        phone.devices = phone.devices.map { if (it == d) it.copy(paused = !it.paused) else it }
                        if (d.paused) userPaused -= d.deviceID else userPaused += d.deviceID
                    }
                }
                phone.apply(reason)
                if (reason != null) assertTrue("blocked: every device paused", phone.devices.all { it.paused })
            }
            phone.apply(null)
            assertEquals(userPaused, phone.devices.filter { it.paused }.map { it.deviceID }.toSet())
            assertEquals(null, phone.recorded)
        }
    }
}
