package com.compositioncoach.app.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One capture in the [PhotoJournal]. Enum-valued fields are stored by name (strings) so a renamed or removed
 * enum value in a later version can never make an old line unreadable.
 *
 * @param id unique per capture: the photo's Uri string.
 * @param score the engine's score (the still photo's once analysed, else the live frame's at the shutter).
 * @param verdict `Verdict` name. @param scene `SceneType` name. @param outcome `Outcome` name.
 * @param cueCategoryAtCapture / [cueIdAtCapture] the headline cue showing when the shutter fired (null = none).
 * @param kept true after Keep, false after Retake, null until the user decides (Keep is the default).
 * @param retaken true after Retake.
 * @param cropAspect `CropAspect` name of the last suggested crop the user saved, if any.
 * @param tryNextTimeCategory the after-action card's Try-next-time category (`MetricCategory` name).
 * @param followedCategories categories whose live cue the user followed between the previous capture and this one.
 * @param notHelpful the user tapped "Not helpful" on this card.
 */
data class JournalRecord(
    val id: String,
    val timestampMs: Long,
    val score: Int,
    val verdict: String,
    val scene: String,
    val outcome: String,
    val cueCategoryAtCapture: String? = null,
    val cueIdAtCapture: String? = null,
    val kept: Boolean? = null,
    val retaken: Boolean = false,
    val cropAspect: String? = null,
    val favourite: Boolean = false,
    val tryNextTimeCategory: String? = null,
    val followedCategories: List<String> = emptyList(),
    val notHelpful: Boolean = false,
) {
    /** The photo's Uri string ([id]). */
    val photoUri: String get() = id

    fun toJson(): String = MiniJson.encode(
        linkedMapOf(
            "id" to id,
            "timestamp" to timestampMs,
            "score" to score,
            "verdict" to verdict,
            "scene" to scene,
            "outcome" to outcome,
            "cueCategoryAtCapture" to cueCategoryAtCapture,
            "cueIdAtCapture" to cueIdAtCapture,
            "kept" to kept,
            "retaken" to retaken,
            "cropAspect" to cropAspect,
            "favourite" to favourite,
            "tryNextTimeCategory" to tryNextTimeCategory,
            "followed" to followedCategories,
            "notHelpful" to notHelpful,
        ),
    )

    companion object {
        /** Null when the line is not a journal record (corrupt, torn, or missing the id). */
        fun fromJson(line: String): JournalRecord? {
            val m = MiniJson.parseObject(line) ?: return null
            val id = m["id"] as? String ?: return null
            return JournalRecord(
                id = id,
                timestampMs = (m["timestamp"] as? Number)?.toLong() ?: 0L,
                score = (m["score"] as? Number)?.toInt() ?: 0,
                verdict = m["verdict"] as? String ?: "",
                scene = m["scene"] as? String ?: "",
                outcome = m["outcome"] as? String ?: "",
                cueCategoryAtCapture = m["cueCategoryAtCapture"] as? String,
                cueIdAtCapture = m["cueIdAtCapture"] as? String,
                kept = m["kept"] as? Boolean,
                retaken = m["retaken"] as? Boolean ?: false,
                cropAspect = m["cropAspect"] as? String,
                favourite = m["favourite"] as? Boolean ?: false,
                tryNextTimeCategory = m["tryNextTimeCategory"] as? String,
                followedCategories = (m["followed"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                notHelpful = m["notHelpful"] as? Boolean ?: false,
            )
        }
    }
}

/**
 * The local photo journal: one [JournalRecord] per capture, as JSON lines in `filesDir/journal.jsonl`. Feeds
 * the "Your eye" screen; never leaves the device (backup is off for the whole app).
 *
 * **Format.** Append-only: [append] writes a line, [update] appends the changed record again with the same
 * id, and reading folds lines by id (the last line wins, the first line fixes the order). Lines that do not
 * parse are skipped. The file is rewritten compacted (one line per record, oldest dropped beyond
 * [maxRecords]) when there are more than [maxRecords] records or twice that many lines.
 *
 * All file work runs on [ioDispatcher] under one [Mutex]; the parsed records are cached in memory after the
 * first read, so [records] and [read] cost nothing after that.
 */
class PhotoJournal(
    private val file: File,
    private val maxRecords: Int = MAX_RECORDS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val cache = MutableStateFlow<List<JournalRecord>?>(null)
    private var linesOnDisk = 0

    /** Every record, oldest first; emits again after each change. */
    val records: Flow<List<JournalRecord>> = flow {
        read()
        emitAll(cache.filterNotNull())
    }

    suspend fun read(): List<JournalRecord> = locked { loadLocked() }

    suspend fun append(record: JournalRecord) = locked {
        val current = loadLocked()
        val existing = current.indexOfFirst { it.id == record.id }
        // Same order rule as the fold on read: a repeated id keeps its first position.
        val next = if (existing >= 0) current.toMutableList().also { it[existing] = record } else current + record
        appendLineLocked(record)
        publishLocked(next)
    }

    /** Applies [transform] to the record with [id]; false when there is none (e.g. the journal was reset). */
    suspend fun update(id: String, transform: (JournalRecord) -> JournalRecord): Boolean = locked {
        val current = loadLocked()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@locked false
        val changed = transform(current[index]).copy(id = id)
        if (changed == current[index]) return@locked true
        appendLineLocked(changed)
        publishLocked(current.toMutableList().also { it[index] = changed })
        true
    }

    suspend fun find(id: String): JournalRecord? = read().firstOrNull { it.id == id }

    /** "Reset coaching preferences": deletes every record. */
    suspend fun clear() = locked {
        file.delete()
        linesOnDisk = 0
        cache.value = emptyList()
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun loadLocked(): List<JournalRecord> {
        cache.value?.let { return it }
        val byId = LinkedHashMap<String, JournalRecord>()
        var lines = 0
        if (file.exists()) {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                lines++
                JournalRecord.fromJson(line)?.let { byId[it.id] = it }
            }
        }
        linesOnDisk = lines
        val records = byId.values.toList()
        cache.value = records
        if (records.size > maxRecords) publishLocked(records)
        return cache.value ?: records
    }

    private fun appendLineLocked(record: JournalRecord) {
        file.parentFile?.mkdirs()
        file.appendText(record.toJson() + "\n")
        linesOnDisk++
    }

    /** Publishes [records] (capped) and compacts the file when it has grown past the limits. */
    private fun publishLocked(records: List<JournalRecord>) {
        val capped = if (records.size > maxRecords) records.takeLast(maxRecords) else records
        if (records.size > maxRecords || linesOnDisk > maxRecords * 2) rewriteLocked(capped)
        cache.value = capped
    }

    private fun rewriteLocked(records: List<JournalRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { out -> records.forEach { out.write(it.toJson()); out.write("\n") } }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        linesOnDisk = records.size
    }

    companion object {
        const val MAX_RECORDS = 2_000
        const val FILE_NAME = "journal.jsonl"
    }
}
