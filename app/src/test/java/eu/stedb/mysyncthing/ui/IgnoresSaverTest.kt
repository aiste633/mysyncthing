package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ignore editor keeps its text over a recreated activity only while small:
 * a large one in saved state would crash the app as it goes to the background
 * (Android takes about 1 MB for all of it); it is then loaded again.
 */
class IgnoresSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(e: IgnoresEdit): IgnoresEdit? = with(EDIT_SAVER) { scope.save(e) }?.let { EDIT_SAVER.restore(it) }

    @Test
    fun smallEditIsKept() {
        val e = IgnoresEdit("*.tmp", TextFieldValue("*.tmp\n!keep", TextRange(3, 5)))
        assertEquals(e, roundTrip(e))
    }

    @Test
    fun largeEditIsLoadedAgain() {
        val half = "a".repeat(SAVED_CHARS / 2)
        assertEquals(half.length * 2, roundTrip(IgnoresEdit(half, TextFieldValue(half)))?.let { it.original.length + it.text.text.length })
        assertNull(with(EDIT_SAVER) { scope.save(IgnoresEdit(half, TextFieldValue(half + "b"))) })
        // The largest file the editor takes is far over it.
        assertNull(with(EDIT_SAVER) { scope.save(IgnoresEdit("", TextFieldValue("a".repeat(Limits.IGNORES.n)))) })
    }
}
