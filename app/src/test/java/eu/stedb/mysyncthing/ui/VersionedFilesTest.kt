package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.FileVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Restore versions reads and sorts the version times once per load ([versionedFiles]) and only filters
 * while the user types ([matching]). Up to 0.5.0 the screen did all of it on every redraw; that code is
 * kept here as [asBefore], and the list must be the same for any versions, range and search text.
 */
class VersionedFilesTest {
    private fun v(time: String, size: Long = 1) = FileVersion(versionTime = time, modTime = time, size = size)

    /** What the screen shows and restores per file: its path, its versions in order, the one picked first. */
    private data class Row(val path: String, val versions: List<FileVersion>, val newest: FileVersion)

    private fun rows(versions: Map<String, List<FileVersion>>, since: Instant?, query: String) =
        versionedFiles(versions).matching(since, query).map { Row(it.path, it.versions, it.versions.first()) }

    /** The screen's code up to 0.5.0, with the range's start passed in. */
    private fun asBefore(versions: Map<String, List<FileVersion>>, since: Instant?, query: String): List<Row> {
        fun instant(rfc3339: String): Instant? = runCatching { OffsetDateTime.parse(rfc3339).toInstant() }.getOrNull()
        return versions.mapNotNull { (path, list) ->
            val sorted = list.sortedByDescending { instant(it.versionTime) }
            val inRange = sorted.filter { since == null || (instant(it.versionTime) ?: Instant.MIN) >= since }
            if (inRange.isEmpty() || (query.isNotBlank() && query.trim().lowercase() !in path.lowercase())) null else Triple(path, sorted, inRange)
        }.sortedByDescending { instant(it.third.first().versionTime) }.map { Row(it.first, it.second, it.third.first()) }
    }

    private val now: Instant = Instant.parse("2026-11-22T10:00:00Z")

    @Test
    fun newestFirstInAFileAndAcrossFiles() {
        val versions = linkedMapOf(
            "a/old.txt" to listOf(v("2026-10-01T08:00:00Z"), v("2026-10-03T08:00:00Z")),
            "b/new.txt" to listOf(v("2026-11-20T08:00:00+01:00"), v("2026-11-21T08:00:00+01:00"), v("2026-11-19T08:00:00+01:00")),
            "c/unreadable.txt" to listOf(v("yesterday")),
            "d/none.txt" to emptyList(),
        )
        val all = versionedFiles(versions)
        assertEquals(listOf("b/new.txt", "a/old.txt", "c/unreadable.txt"), all.map { it.path })
        assertEquals(listOf("2026-11-21T08:00:00+01:00", "2026-11-20T08:00:00+01:00", "2026-11-19T08:00:00+01:00"), all[0].versions.map { it.versionTime })
        assertEquals(Instant.parse("2026-11-21T07:00:00Z"), all[0].newest)
        assertEquals(null, all[2].newest)
    }

    @Test
    fun theRangeCountsTheNewestVersionAndIncludesItsStart() {
        val all = versionedFiles(
            mapOf(
                "week.txt" to listOf(v("2026-11-15T10:00:00Z"), v("2026-01-01T00:00:00Z")),
                "month.txt" to listOf(v("2026-11-15T09:59:59Z")),
                "unreadable.txt" to listOf(v("")),
            ),
        )
        val week = now.minus(7, ChronoUnit.DAYS)
        assertEquals(listOf("week.txt"), all.matching(week, "").map { it.path })
        // A file in the range keeps all its versions, the older ones too.
        assertEquals(2, all.matching(week, "").single().versions.size)
        assertEquals(listOf("week.txt", "month.txt"), all.matching(now.minus(30, ChronoUnit.DAYS), "").map { it.path })
        assertEquals(listOf("week.txt", "month.txt", "unreadable.txt"), all.matching(null, "").map { it.path })
    }

    @Test
    fun theSearchIgnoresCaseAndOuterSpaces() {
        val all = versionedFiles(mapOf("Docs/Budget.ods" to listOf(v("2026-11-20T10:00:00Z")), "notes.md" to listOf(v("2026-11-21T10:00:00Z"))))
        assertEquals(listOf("Docs/Budget.ods"), all.matching(null, "  bUDGET ").map { it.path })
        assertEquals(listOf("notes.md", "Docs/Budget.ods"), all.matching(null, " \t").map { it.path })
        assertEquals(emptyList<String>(), all.matching(null, "bud get").map { it.path })
    }

    private val time: Gen<String> = oneOf(
        // Around the ranges' starts, to the second, in several offsets: the same instant written differently, and ties.
        {
            val at = now.minus(pick(0L, 6L, 7L, 8L, 29L, 30L, 31L, 400L)(this), ChronoUnit.DAYS).plusSeconds(nextLong(-2, 3))
            at.atOffset(ZoneOffset.ofHours(nextInt(-12, 15))).toString()
        },
        { now.minusSeconds(nextLong(0, 40L * 86_400)).toString() },
        pick("", "never", "0001-01-01T00:00:00Z", "2026-11-22", "2026-11-22T10:00:00", "+999999999-12-31T23:59:59Z"),
    )

    private val versionMap: Gen<Map<String, List<FileVersion>>> = {
        val paths = List(nextInt(0, 8)) { pick("a", "A", "Docs", "docs", "b c", "")(this) + pick("", "/", "/x/")(this) + chars("abAB .é", 0, 4) }
        // LinkedHashMap, as the JSON decoder gives: the core's order is kept.
        paths.distinct().associateWithTo(LinkedHashMap()) { List(nextInt(0, 6)) { FileVersion(time(this), "", nextLong(0, 3)) } }
    }

    private data class Case(val versions: Map<String, List<FileVersion>>, val since: Instant?, val query: String)

    private val case: Gen<Case> = {
        val map = versionMap(this)
        val since = oneOf<Instant?>(
            { null },
            { now.minus(pick(7L, 30L)(this), ChronoUnit.DAYS) },
            { now.minusSeconds(nextLong(0, 40L * 86_400)) },
            pick(Instant.MIN, Instant.MAX, Instant.EPOCH),
        )(this)
        val query = oneOf<String>(
            pick("", " ", "\t\n", "a", "A ", " docs", "DOCS/", "b c", "é", "É", "zzz"),
            { map.keys.randomOrNull(this)?.let { it.substring(nextInt(0, it.length + 1)).let { tail -> if (nextBoolean()) tail.uppercase() else " $tail " } }.orEmpty() },
            { chars("abAB /.é ", 0, 3) },
        )(this)
        Case(map, since, query)
    }

    @Test
    fun sameListAsTheScreenUpTo050() = forAll(case, runs = 50_000) { (versions, since, query) ->
        assertEquals(asBefore(versions, since, query), rows(versions, since, query))
    }
}
