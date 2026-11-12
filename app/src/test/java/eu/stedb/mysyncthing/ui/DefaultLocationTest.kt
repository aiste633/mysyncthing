package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The default location for new folders (Syncthing/) can be opened but never be a folder,
 * nor can a folder hold it; paths are the same folder whatever their case, alias or
 * trailing "/"; a new folder starts as "New folder", the first one free.
 */
class DefaultLocationTest {
    private val root = internalStorage

    // --- Generators: a path is a storage, then parts; its spellings change only what the phone ignores.

    private val storages = listOf(root, "/sdcard", "/storage/self/primary", "/storage/1234-ABCD")
    private val names = listOf("Syncthing", "DCIM", "Camera", "Music", "Sync", "Phone", "my photos", "Ünï 😀", "Android", "media", "data", "a")

    private data class P(val storage: Int, val parts: List<String>)

    private val p: Gen<P> = { P(nextInt(storages.size), List(nextInt(0, 4)) { names.random(this) }) }

    private fun P.path(trailing: Boolean = false) = storages[storage] + parts.joinToString("") { "/$it" } + if (trailing) "/" else ""

    /** The same folder spelled another way: other case in the parts, another name for internal storage, a trailing "/". */
    private fun Random.respell(x: P): String {
        val s = if (x.storage < 3) nextInt(3) else x.storage
        val parts = x.parts.map { part -> part.map { if (nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("") }
        return P(s, parts).path(trailing = nextBoolean())
    }

    private fun folders(vararg paths: String) = paths.mapIndexed { i, path -> Folder(id = "f$i", label = "F$i", path = path) }

    /** The model: lower case, internal storage spelled out, no trailing "/". */
    private fun model(path: String) = folderPath(path).lowercase()

    @Test
    fun theDefaultLocationAndWhatHoldsItAreRefused() = forAll({ p(this) to p(this) }) { (base, x) ->
        val b = model(base.path())
        val m = model(x.path())
        val refused = defaultLocationError(x.path(), base.path()) != null
        assertEquals(m == b || b.startsWith("$m/"), refused)
    }

    @Test
    fun insideTheDefaultLocationIsFine() = forAll({ p(this) to p(this).parts }) { (base, more) ->
        val inside = P(base.storage, base.parts + more + "Laptop")
        assertNull(defaultLocationError(inside.path(), base.path()))
        assertNotNull(defaultLocationError(base.path(trailing = true), base.path()))
    }

    @Test
    fun spellingNeverChangesTheVerdict() = forAll({ Triple(p(this), p(this), nextLong()) }) { (base, x, seed) ->
        val r = Random(seed)
        val others = folders(r.respell(P(x.storage, x.parts.take(1))), "$root/Documents")
        val verdict = pickProblem(x.path(), others, base.path())?.en()
        val again = r.respell(x)
        assertEquals("$again", verdict, pickProblem(again, others, r.respell(base))?.en())
        assertEquals("$again", folderPathError(x.path(), others.map { it.path }) == null, folderPathError(again, others.map { it.path }) == null)
    }

    /** The import's rule (no default location) is the form's minus that one rule. */
    @Test
    fun theFormIsTheImportPlusTheDefaultLocation() = forAll({ Triple(p(this), p(this), p(this)) }) { (base, x, other) ->
        val others = folders(other.path())
        val form = pickProblem(x.path(), others, base.path())
        val import = folderPathError(x.path(), others.map { it.path })
        assertEquals(import == null && defaultLocationError(x.path(), base.path()) == null, form == null)
    }

    /** Fuzz: any text in either place gets a reason or passes, and never throws. */
    @Test
    fun anyTextIsAnswered() = forAll({ anyText(this) to oneOf(anyText, { p(this).path() })(this) }) { (path, base) ->
        pickProblem(path, folders("$root/DCIM", "/storage/1234-ABCD/Music/"), base)
        defaultLocationError(base, path)
        folderPathError(path, listOf(base))
    }

    @Test
    fun overlapIsSymmetricAndFoldsCase() = forAll({ p(this) to p(this) }) { (a, b) ->
        assertEquals(overlaps(folderPath(a.path()), folderPath(b.path())), overlaps(folderPath(b.path()), folderPath(a.path())))
        val ma = model(a.path())
        val mb = model(b.path())
        assertEquals(ma == mb || ma.startsWith("$mb/") || mb.startsWith("$ma/"), overlaps(folderPath(a.path()), folderPath(b.path())))
    }

    // --- The name a new folder starts with.

    private val base = "$root/Syncthing"

    @Test
    fun newFolderNameIsTheFirstFree() {
        assertEquals("New folder", newFolderName("New folder", base, emptyList()) { false })
        assertEquals("New folder 2", newFolderName("New folder", base, emptyList()) { it == "$base/New folder" })
        assertEquals("New folder 2", newFolderName("New folder", base, folders("/sdcard/syncthing/new folder")) { false })
        assertEquals("New folder 2", newFolderName("New folder", base, folders("$base/New folder/Photos")) { false })
        assertEquals("New folder 3", newFolderName("New folder", base, folders("$base/New folder 2")) { it == "$base/New folder" })
        assertEquals("Neuer Ordner", newFolderName("Neuer Ordner", base, emptyList()) { false })
        // All 999 taken, or a folder holds the whole default location: the plain name, which the form then refuses.
        assertEquals("New folder", newFolderName("New folder", base, emptyList()) { true })
        assertEquals("New folder", newFolderName("New folder", base, folders(base)) { false })
    }

    @Test
    fun newFolderNameIsFreeWhenOneIs() = forAll({ List(nextInt(0, 6)) { nextInt(1, 6) } to List(nextInt(0, 3)) { nextInt(1, 6) } }) { (onDisk, synced) ->
        fun at(n: Int) = "$base/" + if (n == 1) "New folder" else "New folder $n"
        val name = newFolderName("New folder", base, folders(*synced.map { at(it).lowercase() }.toTypedArray())) { it in onDisk.map(::at) }
        val n = if (name == "New folder") 1 else name.removePrefix("New folder ").toInt()
        assertFalse(n in onDisk || n in synced)
        assertTrue("the first free", (1 until n).all { it in onDisk || it in synced })
        assertNull(pickProblem(suggestedPath(name, base), folders(*synced.map(::at).toTypedArray()), base))
    }

    // --- Quick picks: the chosen Syncthing/<name> is not repeated.

    @Test
    fun theChosenSuggestionIsNotAQuickPick() {
        val picks = listOf("$root/DCIM/Camera", "$root/Download")
        assertEquals(picks, quickPickChips("$base/New folder", picks, "$base/New folder"))
        assertEquals(picks, quickPickChips("$base/New folder", picks, "$root/syncthing/new folder"))
        assertEquals(listOf("$base/Recipes") + picks, quickPickChips("$base/Recipes", picks, "$root/Download"))
        assertEquals(picks, quickPickChips(null, picks, ""))
        assertEquals(listOf("$root/Download", "$root/DCIM/Camera"), quickPickChips("$root/Download", picks, "$root/Pictures"))
    }
}
