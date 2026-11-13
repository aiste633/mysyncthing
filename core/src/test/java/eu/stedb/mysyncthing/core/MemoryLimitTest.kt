package eu.stedb.mysyncthing.core

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryLimitTest {
    private fun exit(at: Long, reason: Int = ApplicationExitInfo.REASON_OTHER, description: String? = "MemoryLimiter: rss over limit") =
        ProcessExit(reason, description, at, 300_000)

    // Only REASON_OTHER with "MemoryLimiter" counts: low memory, crashes and a user stop are other notices or none.
    @Test
    fun onlyTheMemoryLimiterCounts() {
        assertEquals(true, exit(1).byMemoryLimit())
        assertEquals(false, exit(1, ApplicationExitInfo.REASON_LOW_MEMORY).byMemoryLimit())
        assertEquals(false, exit(1, ApplicationExitInfo.REASON_CRASH_NATIVE).byMemoryLimit())
        assertEquals(false, exit(1, description = "user request after error").byMemoryLimit())
        assertEquals(false, exit(1, description = null).byMemoryLimit())
    }

    // The newest end after the last notice is reported; ends up to and including that time are not reported again.
    @Test
    fun newestUnseenEnd() {
        val exits = listOf(exit(100), exit(300), exit(200), exit(400, ApplicationExitInfo.REASON_USER_REQUESTED, "stop"))
        assertEquals(300L, exits.newestMemoryLimitEnd(seenUntil = 150)?.timestamp)
        assertNull(exits.newestMemoryLimitEnd(seenUntil = 300))
        assertNull(emptyList<ProcessExit>().newestMemoryLimitEnd(seenUntil = 0))
    }
}
