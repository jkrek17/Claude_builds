package com.compositioncoach.app.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(i: Int, score: Int = 50 + i) = JournalRecord(
        id = "content://media/external/images/media/$i",
        timestampMs = 1_700_000_000_000L + i,
        score = score,
        verdict = "GOOD",
        scene = "PORTRAIT",
        outcome = "PORTRAIT",
        cueCategoryAtCapture = if (i % 2 == 0) "HORIZON" else null,
        cueIdAtCapture = if (i % 2 == 0) "horizon.tilt" else null,
        followedCategories = listOf("SUBJECT_PLACEMENT"),
    )

    private fun journal(file: File, max: Int = PhotoJournal.MAX_RECORDS, dispatcher: CoroutineDispatcher) =
        PhotoJournal(file, max, dispatcher)

    @Test
    fun `records round-trip through JSON, including awkward strings`() {
        val r = record(1).copy(
            verdict = "quote\" back\\slash\nnew line\ttab \u0001 é",
            kept = false,
            retaken = true,
            cropAspect = "SQUARE",
            favourite = true,
            tryNextTimeCategory = "LIGHT",
            notHelpful = true,
        )
        assertEquals(r, JournalRecord.fromJson(r.toJson()))
        assertNull(JournalRecord.fromJson("{\"id\":\"x\""))
        assertNull(JournalRecord.fromJson("not json"))
        assertNull(JournalRecord.fromJson("{\"score\":3}"))
    }

    @Test
    fun `append and read survive a fresh instance`() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val file = File(tmp.root, "journal.jsonl")
        val j = journal(file, dispatcher = d)
        j.append(record(1))
        j.append(record(2))
        assertEquals(listOf(record(1), record(2)), j.read())
        assertEquals(listOf(record(1), record(2)), journal(file, dispatcher = d).read())
        assertEquals(2, file.readLines().size)
    }

    @Test
    fun `update rewrites the record by appending, and the last line wins on read`() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val file = File(tmp.root, "journal.jsonl")
        val j = journal(file, dispatcher = d)
        j.append(record(1))
        j.append(record(2))
        assertTrue(j.update(record(1).id) { it.copy(kept = true, cropAspect = "FOUR_FIVE") })
        assertFalse(j.update("content://nope") { it.copy(kept = true) })
        val expected = listOf(record(1).copy(kept = true, cropAspect = "FOUR_FIVE"), record(2))
        assertEquals(expected, j.read())
        assertEquals(expected, journal(file, dispatcher = d).read())
        assertEquals(3, file.readLines().size)
    }

    @Test
    fun `a torn or corrupt line is skipped, not fatal`() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val file = File(tmp.root, "journal.jsonl")
        file.writeText(record(1).toJson() + "\n{\"id\":\"broken\n" + record(2).toJson() + "\n{\"id\":")
        assertEquals(listOf(record(1), record(2)), journal(file, dispatcher = d).read())
    }

    @Test
    fun `the journal is capped and compacted, dropping the oldest`() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val file = File(tmp.root, "journal.jsonl")
        val j = journal(file, max = 5, dispatcher = d)
        (1..7).forEach { j.append(record(it)) }
        val ids = j.read().map { it.id }
        assertEquals((3..7).map { record(it).id }, ids)
        assertEquals(ids, journal(file, max = 5, dispatcher = d).read().map { it.id })
        // Updates also trigger compaction once the file has twice the cap in lines.
        repeat(12) { n -> j.update(record(7).id) { it.copy(score = n) } }
        assertTrue(file.readLines().size <= 10)
        assertEquals(11, journal(file, max = 5, dispatcher = d).read().last().score)
    }

    @Test
    fun `clear empties the journal and the records flow follows every change`() = runTest {
        val d = UnconfinedTestDispatcher(testScheduler)
        val file = File(tmp.root, "journal.jsonl")
        val j = journal(file, dispatcher = d)
        assertEquals(emptyList<JournalRecord>(), j.records.first())
        j.append(record(1))
        assertEquals(listOf(record(1)), j.records.first())
        j.clear()
        assertEquals(emptyList<JournalRecord>(), j.records.first())
        assertFalse(file.exists())
        assertEquals(emptyList<JournalRecord>(), journal(file, dispatcher = d).read())
    }

    @Test
    fun `MiniJson parses what it writes`() {
        val fields = linkedMapOf<String, Any?>("a" to "x", "b" to 3, "c" to -2.5, "d" to true, "e" to null, "f" to listOf("p", "q"), "g" to emptyList<String>())
        val parsed = MiniJson.parseObject(MiniJson.encode(fields))!!
        assertEquals("x", parsed["a"])
        assertEquals(3L, parsed["b"])
        assertEquals(-2.5, parsed["c"])
        assertEquals(true, parsed["d"])
        assertTrue(parsed.containsKey("e") && parsed["e"] == null)
        assertEquals(listOf("p", "q"), parsed["f"])
        assertEquals(emptyList<String>(), parsed["g"])
        assertEquals(emptyMap<String, Any?>(), MiniJson.parseObject(" { } "))
        assertNull(MiniJson.parseObject("{} trailing"))
        assertNull(MiniJson.parseObject("{\"a\":tru}"))
    }
}
