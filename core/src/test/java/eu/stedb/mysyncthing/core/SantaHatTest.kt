package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SantaHatTest {
    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun wornFrom22DecemberTo6January() {
        listOf("2026-12-22", "2026-12-25", "2026-12-31", "2027-01-01", "2027-01-06", "2028-12-22").forEach { assertTrue(it, santaHat(day(it))) }
        listOf("2026-12-21", "2027-01-07", "2026-10-30", "2026-06-22", "2026-02-06", "2026-11-30").forEach { assertFalse(it, santaHat(day(it))) }
    }

    @Test
    fun nextChangeIsTheFirstDayThatDiffers() {
        assertEquals(day("2026-12-22"), santaHatChange(day("2026-10-30")))
        assertEquals(day("2026-12-22"), santaHatChange(day("2026-12-21")))
        assertEquals(day("2027-01-07"), santaHatChange(day("2026-12-22")))
        assertEquals(day("2027-01-07"), santaHatChange(day("2026-12-31")))
        assertEquals(day("2027-01-07"), santaHatChange(day("2027-01-01")))
        assertEquals(day("2027-12-22"), santaHatChange(day("2027-01-07")))
        // Every day of a leap year: the change is later, and the day before it still has the old state.
        generateSequence(day("2028-01-01")) { it.plusDays(1) }.takeWhile { it.year == 2028 }.forEach {
            val next = santaHatChange(it)
            assertTrue(next > it)
            assertEquals(santaHat(it), santaHat(next.minusDays(1)))
            assertTrue(santaHat(it) != santaHat(next))
        }
    }
}
