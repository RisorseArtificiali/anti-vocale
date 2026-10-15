package com.antivocale.app.data

import com.antivocale.app.data.local.LogEntity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * TASK-844 (GH #158 part 2): the history payload of the backup archive.
 * One NDJSON entry (history.ndjson, one full-row JSON object per line:
 * streaming-friendly on both ends, one malformed row costs one line, not
 * the whole archive). The codec is column-complete by construction:
 * [rowToJson] writes every LogEntity field and [rowFromJson] reads it
 * back through the same key set, so the projection-completeness trap the
 * lean list queries document (a dropped column reads as its default)
 * cannot happen here: a missing key in the JSON reads the entity's own
 * default, and a key the entity no longer has is ignored.
 *
 * WHAT THE FORMAT DOES NOT DO: no sqlite snapshot (a raw DB file replaces
 * local history and breaks on schema drift; the merge import is the
 * designed semantics), no tombstones or change tracking (a restore is a
 * merge, never a sync), no audio (the sandbox copies are transient by
 * design; imported rows carry filePath for provenance only).
 */
object BackupHistoryCodec {

    const val HISTORY_ENTRY = "history.ndjson"
    private const val PAGE_SIZE = 200

    /**
     * Streams the whole history into the archive: the caller supplies a
     * suspend page fetcher (the DAO's keyset query) so the codec never
     * holds more than one page in memory; rows are written one JSON line
     * at a time.
     */
    suspend fun writeHistoryEntry(
        zip: ZipOutputStream,
        pageFetcher: suspend (beforeTimestamp: Long, limit: Int) -> List<LogEntity>,
    ) {
        zip.putNextEntry(ZipEntry(HISTORY_ENTRY))
        val buffer = ByteArrayOutputStream()
        var before = Long.MAX_VALUE
        while (true) {
            val page = pageFetcher(before, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { row ->
                buffer.write(rowToJson(row).toString().toByteArray(Charsets.UTF_8))
                buffer.write('\n'.code)
            }
            zip.write(buffer.toByteArray())
            buffer.reset()
            before = page.last().timestamp
            if (page.size < PAGE_SIZE) break
            // Keyset walk: pageBefore uses timestamp < before, so two rows
            // sharing the exact same timestamp would strand the second one;
            // the nudge keeps the walk total (the timestamp index keeps it
            // cheap).
            before -= 1
        }
        zip.closeEntry()
    }

    /**
     * Parses the history payload. Malformed lines are dropped, not fatal
     * (a one-line corruption costs one row); an empty payload reads as an
     * empty list. Blocking convenience over the suspend walk (the tests'
     * entry point; production rides the chunked variant).
     */
    fun readHistoryEntry(bytes: ByteArray): List<LogEntity> =
        kotlinx.coroutines.runBlocking { readHistoryEntryWithDropped(bytes).first }

    /**
     * TASK-844: the import arm's result: the parsed rows plus how many
     * non-blank lines were dropped (the report the import toast shows).
     * Full-materialization variant for the codec's own API and tests; the
     * import path rides [readHistoryEntryChunked] instead.
     */
    suspend fun readHistoryEntryWithDropped(bytes: ByteArray): Pair<List<LogEntity>, Int> {
        val rows = mutableListOf<LogEntity>()
        val dropped = readHistoryEntryChunked(bytes) { chunk -> rows.addAll(chunk) }
        return rows to dropped
    }

    /**
     * The import's streaming parse: rows flush to [onChunk] in [chunkSize]
     * batches (the insert page; the caller never holds the whole history
     * in the heap, the 256MB lesson) and the return value is the dropped
     * line count. The emitter is suspend because the caller's chunk
     * handler is the DAO insert; each chunk is an INDEPENDENT list (the
     * emitter may retain what it receives). Reads line-by-line: no
     * full-text copy.
     */
    suspend fun readHistoryEntryChunked(
        bytes: ByteArray,
        chunkSize: Int = PAGE_SIZE,
        onChunk: suspend (List<LogEntity>) -> Unit,
    ): Int = readHistoryEntryChunked(
        bytes.inputStream().bufferedReader(Charsets.UTF_8), chunkSize, onChunk)

    /** The [readHistoryEntryChunked] core over a live reader (the zip
     *  stream's, via BackupArchiveCodec.openHistoryReader; closing the
     *  reader closes the stream). */
    suspend fun readHistoryEntryChunked(
        reader: java.io.BufferedReader,
        chunkSize: Int = PAGE_SIZE,
        onChunk: suspend (List<LogEntity>) -> Unit,
    ): Int {
        var dropped = 0
        val chunk = ArrayList<LogEntity>(chunkSize)
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    chunk.add(rowFromJson(JSONObject(line)))
                } catch (e: Exception) {
                    dropped++
                    return@forEach
                }
                if (chunk.size >= chunkSize) {
                    onChunk(chunk.toList())
                    chunk.clear()
                }
            }
        }
        if (chunk.isNotEmpty()) onChunk(chunk.toList())
        return dropped
    }

    /** Column-complete by construction: every LogEntity field, same key on both ends. */
    internal fun rowToJson(row: LogEntity): JSONObject = JSONObject()
        .put("id", row.id)
        .put("timestamp", row.timestamp)
        .put("taskId", row.taskId)
        .put("type", row.type)
        .put("status", row.status)
        .put("prompt", row.prompt)
        .put("result", row.result)
        .put("errorMessage", row.errorMessage ?: JSONObject.NULL)
        .put("durationMs", row.durationMs)
        .put("filePath", row.filePath ?: JSONObject.NULL)
        .put("audioDurationSeconds", row.audioDurationSeconds)
        .put("sourcePackageName", row.sourcePackageName ?: JSONObject.NULL)
        .put("senderName", row.senderName ?: JSONObject.NULL)
        .put("isPartial", row.isPartial)
        .put("failedChunkCount", row.failedChunkCount)
        .put("modelName", row.modelName ?: JSONObject.NULL)
        .put("rawTranscript", row.rawTranscript ?: JSONObject.NULL)
        .put("summary", row.summary ?: JSONObject.NULL)
        .put("summarySkipReason", row.summarySkipReason ?: JSONObject.NULL)
        .put("segments", row.segments ?: JSONObject.NULL)
        .put("failureContext", row.failureContext ?: JSONObject.NULL)
        .put("processingContext", row.processingContext ?: JSONObject.NULL)
        .put("firstPassTranscript", row.firstPassTranscript ?: JSONObject.NULL)
        .put("detectedLanguage", row.detectedLanguage ?: JSONObject.NULL)
        .put("languagePin", row.languagePin ?: JSONObject.NULL)
        .put("savedFolderName", row.savedFolderName ?: JSONObject.NULL)
        .put("savedFileName", row.savedFileName ?: JSONObject.NULL)
        .put("saveFailureReason", row.saveFailureReason ?: JSONObject.NULL)
        // TASK-842's column joined after the format shipped; a missing key
        // on import reads null (backwards compatible, no version bump).
        .put("contentHash", row.contentHash ?: JSONObject.NULL)

    /** The inverse: a missing key reads the entity's own default (never a silent 0-vs-null swap). */
    internal fun rowFromJson(o: JSONObject): LogEntity = LogEntity(
        id = o.getString("id"),
        timestamp = o.getLong("timestamp"),
        taskId = o.optString("taskId", ""),
        type = o.optString("type", ""),
        status = o.optString("status", ""),
        prompt = o.optString("prompt", ""),
        result = o.optString("result", ""),
        errorMessage = if (o.isNull("errorMessage")) null else o.optString("errorMessage"),
        durationMs = o.optLong("durationMs", 0),
        filePath = if (o.isNull("filePath")) null else o.optString("filePath"),
        audioDurationSeconds = o.optDouble("audioDurationSeconds", 0.0),
        sourcePackageName = if (o.isNull("sourcePackageName")) null else o.optString("sourcePackageName"),
        senderName = if (o.isNull("senderName")) null else o.optString("senderName"),
        isPartial = o.optBoolean("isPartial", false),
        failedChunkCount = o.optInt("failedChunkCount", 0),
        modelName = if (o.isNull("modelName")) null else o.optString("modelName"),
        rawTranscript = if (o.isNull("rawTranscript")) null else o.optString("rawTranscript"),
        summary = if (o.isNull("summary")) null else o.optString("summary"),
        summarySkipReason = if (o.isNull("summarySkipReason")) null else o.optString("summarySkipReason"),
        segments = if (o.isNull("segments")) null else o.optString("segments"),
        failureContext = if (o.isNull("failureContext")) null else o.optString("failureContext"),
        processingContext = if (o.isNull("processingContext")) null else o.optString("processingContext"),
        firstPassTranscript = if (o.isNull("firstPassTranscript")) null else o.optString("firstPassTranscript"),
        detectedLanguage = if (o.isNull("detectedLanguage")) null else o.optString("detectedLanguage"),
        languagePin = if (o.isNull("languagePin")) null else o.optString("languagePin"),
        savedFolderName = if (o.isNull("savedFolderName")) null else o.optString("savedFolderName"),
        savedFileName = if (o.isNull("savedFileName")) null else o.optString("savedFileName"),
        saveFailureReason = if (o.isNull("saveFailureReason")) null else o.optString("saveFailureReason"),
        contentHash = if (o.isNull("contentHash")) null else o.optString("contentHash"),
    )
}
