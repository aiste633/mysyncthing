package eu.stedb.mysyncthing.ui

import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Test
import eu.stedb.mysyncthing.core.R as CoreR

/** Conflict copies show on their folder's card, in amber after the details; the status stays. */
class ConflictsScreenTest : ScreenTest() {
    private fun folders(s: eu.stedb.mysyncthing.core.Snapshot) = show {
        FoldersScreen(s, summarize(s), vm, onOpen = {}, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = {})
    }

    private fun summarize(s: eu.stedb.mysyncthing.core.Snapshot) = eu.stedb.mysyncthing.core.summarize(Fixtures.core, s, null, true)

    private val two get() = app.resources.getQuantityString(CoreR.plurals.conflicts, 2, 2)

    @Test
    fun theCardCountsTheFoldersCopies() {
        folders(Fixtures.withConflicts)
        compose.onNodeWithText(two, substring = true).assertExists()
        // Only Documents has some.
        compose.onNodeWithText("shared with 1 device · $two", substring = true).assertExists()
    }

    @Test
    fun noCopiesNoText() {
        folders(Fixtures.snapshot)
        compose.onNodeWithText(app.resources.getQuantityString(CoreR.plurals.conflicts, 1, 1).substringAfter(' '), substring = true).assertDoesNotExist()
    }

    @Test
    fun copiesDoNotChangeTheState() {
        assertEquals(Fixtures.summary.state, Fixtures.conflictsSummary.state)
        assertEquals(Fixtures.summary, Fixtures.conflictsSummary.copy(conflicts = 0))
        assertEquals(2, Fixtures.conflictsSummary.conflicts)
    }
}
