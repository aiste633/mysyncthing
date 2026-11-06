package eu.stedb.mysyncthing.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import eu.stedb.mysyncthing.R
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
        compose.onNodeWithText("shared with 1 device\u00A0· $two", substring = true).assertExists()
    }

    @Test
    fun noCopiesNoText() {
        folders(Fixtures.snapshot)
        compose.onNodeWithText(app.resources.getQuantityString(CoreR.plurals.conflicts, 1, 1).substringAfter('\u00A0'), substring = true).assertDoesNotExist()
    }

    @Test
    fun copiesDoNotChangeTheState() {
        assertEquals(Fixtures.summary.state, Fixtures.conflictsSummary.state)
        assertEquals(Fixtures.summary, Fixtures.conflictsSummary.copy(conflicts = 0))
        assertEquals(2, Fixtures.conflictsSummary.conflicts)
    }

    private fun recent(s: eu.stedb.mysyncthing.core.Snapshot, folder: String = "docs") = show { RecentChangesScreen(s, folder, vm, onBack = {}) }

    @Test
    fun recentChangesListsTheCopiesAboveTheChanges() {
        recent(Fixtures.withConflicts)
        compose.onNodeWithTag("conflicts").assertExists()
        compose.onNodeWithText(two).assertExists()
        // The file each came from, with its subfolder, and whose edit was kept (EUYDWRS is the laptop).
        compose.onNodeWithText("budget.ods").assertExists()
        compose.onNodeWithText("notes.md").assertExists()
        compose.onNodeWithText("work · ", substring = true).assertExists()
        compose.onAllNodesWithText(str(R.string.conflict_with, "Laptop"), substring = true).assertCountEquals(2)
        // The folder's limit, here upstream's default.
        compose.onNodeWithText(str(R.string.conflicts_limit_default, "10")).assertExists()
    }

    @Test
    fun theLimitIsTheFolders() {
        fun with(max: Int) = Fixtures.withConflicts.copy(folders = Fixtures.withConflicts.folders.map { if (it.id == "docs") it.copy(maxConflicts = max) else it })
        recent(with(3))
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.conflicts_limit, 3, "3", "10")).assertExists()
    }

    @Test
    fun noCopiesNoBox() {
        recent(Fixtures.withConflicts, folder = "photos")
        compose.onNodeWithTag("conflicts").assertDoesNotExist()
    }

    @Test
    fun theLimitTextForEveryValue() {
        val f = Fixtures.withConflicts
        var max by mutableIntStateOf(0)
        show { RecentChangesScreen(f.copy(folders = f.folders.map { if (it.id == "docs") it.copy(maxConflicts = max) else it }), "docs", vm, onBack = {}) }
        compose.onNodeWithText(str(R.string.conflicts_limit_off, "10")).assertExists()
        max = -1
        compose.onNodeWithText(str(R.string.conflicts_limit_all, "10")).assertExists()
        max = 1
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.conflicts_limit, 1, "1", "10")).assertExists()
    }
}
