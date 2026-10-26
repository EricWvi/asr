package top.onlyquant.tingxie.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

enum class SaveStatus { Saving, Error }

/**
 * The signed-in user's documents. The server is the only source of truth;
 * what was last read is kept on disk so documents can be read offline.
 * Edits apply here at once and reach the server debounced, one request per
 * document at a time, the way frontend/src/lib/save-queue.ts does it.
 */
class Library(private val api: Api, private val dir: File, private val scope: CoroutineScope) {
    /** Summaries, most recently updated first; null until first known. */
    var list by mutableStateOf<List<Doc>?>(null)
        private set

    /** Open documents with their full content, kept in step with edits. */
    private val docs = mutableStateMapOf<Long, Doc>()

    /** Absent means everything is saved. */
    val status = mutableStateMapOf<Long, SaveStatus>()

    private val pending = mutableMapOf<Long, DocPatch>()
    private val timers = mutableMapOf<Long, Job>()
    private val locks = mutableMapOf<Long, Mutex>()

    init {
        dir.mkdirs()
        list = read<List<Doc>>("list.json")
        read<Map<Long, DocPatch>>("pending.json")?.let { saved ->
            pending.putAll(saved)
            for (id in saved.keys) {
                status[id] = SaveStatus.Saving
                scope.launch { flush(id) }
            }
        }
    }

    private inline fun <reified T> read(name: String): T? = runCatching {
        json.decodeFromString<T>(File(dir, name).readText())
    }.getOrNull()

    private inline fun <reified T> write(name: String, value: T) {
        val text = json.encodeToString(value)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, name))
            }
        }
    }

    private fun putList(l: List<Doc>) {
        list = l.sortedWith(compareByDescending<Doc> { it.updated }.thenByDescending { it.id })
        write("list.json", list)
    }

    suspend fun refreshList() {
        val fresh = api.documents()
        // Keep local edits that have not reached the server yet.
        putList(fresh.map { d -> pending[d.id]?.let { d.apply(it) } ?: d })
    }

    fun doc(id: Long): Doc? = docs[id] ?: read<Doc>("doc-$id.json")?.also { docs[id] = it }

    /** Reads a document from the server unless edits to it are still on their way. */
    suspend fun fetch(id: Long): Doc {
        val d = api.document(id)
        val merged = pending[id]?.let { d.apply(it) } ?: d
        docs[id] = merged
        write("doc-$id.json", merged)
        return merged
    }

    suspend fun create(title: String, color: String? = null): Doc {
        val d = api.create(title, color)
        docs[d.id] = d
        write("doc-${d.id}.json", d)
        putList(listOf(d) + (list ?: emptyList()))
        return d
    }

    /** Applies an edit locally and schedules saving it. Colours save at once. */
    fun edit(id: Long, patch: DocPatch) {
        docs[id]?.let { docs[id] = it.apply(patch) }
        list?.let { l -> list = l.map { if (it.id == id) it.apply(patch.copy(content = null, tidyRanges = null)) else it } }
        pending[id] = pending[id]?.merge(patch) ?: patch
        write("pending.json", pending.toMap())
        status[id] = SaveStatus.Saving
        timers.remove(id)?.cancel()
        if (patch.content == null && patch.title == null) {
            scope.launch { flush(id) }
        } else {
            timers[id] = scope.launch {
                delay(DEBOUNCE_MS)
                // Out of the map first: flush cancels the timer it finds there.
                timers.remove(id)
                flush(id)
            }
        }
    }

    fun dirty(id: Long) = pending.containsKey(id) || locks[id]?.isLocked == true

    /** Sends the document's pending edits now and waits until they are saved. */
    suspend fun flush(id: Long) {
        timers.remove(id)?.cancel()
        val lock = locks.getOrPut(id) { Mutex() }
        lock.withLock {
            val patch = pending.remove(id) ?: return
            try {
                val saved = api.update(id, patch)
                docs[id]?.let { docs[id] = it.copy(updatedAt = saved.updatedAt) }
                docs[id]?.let { write("doc-$id.json", it) }
                list?.let { l ->
                    putList(l.map { if (it.id == id) it.copy(updatedAt = saved.updatedAt, snippet = saved.snippet) else it })
                }
                if (!pending.containsKey(id)) status.remove(id)
            } catch (e: ApiError) {
                if (e.status == 404) {
                    // Deleted elsewhere; nothing left to save into.
                    status.remove(id)
                } else {
                    retry(id, patch)
                }
            } catch (e: IOException) {
                retry(id, patch)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Not sent, or not known to be: keep it for the next flush.
                pending[id] = pending[id]?.let { patch.merge(it) } ?: patch
                throw e
            } finally {
                write("pending.json", pending.toMap())
            }
        }
        if (pending.containsKey(id) && timers[id] == null) flush(id)
    }

    private fun retry(id: Long, patch: DocPatch) {
        pending[id] = pending[id]?.let { patch.merge(it) } ?: patch
        status[id] = SaveStatus.Error
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            delay(RETRY_MS)
            timers.remove(id)
            flush(id)
        }
    }

    fun flushAll() {
        for (id in pending.keys.toList()) scope.launch { flush(id) }
    }

    /** Drops unsaved edits, for a document that is being thrown away. */
    fun discard(id: Long) {
        timers.remove(id)?.cancel()
        pending.remove(id)
        status.remove(id)
        write("pending.json", pending.toMap())
    }

    suspend fun delete(id: Long) {
        discard(id)
        api.delete(id)
        list?.let { l -> list = l.filter { it.id != id } }
        write("list.json", list)
    }

    suspend fun restore(id: Long) {
        api.restore(id)
        runCatching { refreshList() }
    }

    /** Removes what is cached on disk, on signing out. */
    fun clear() {
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    companion object {
        const val DEBOUNCE_MS = 800L
        const val RETRY_MS = 5000L
    }
}

fun Doc.apply(p: DocPatch) = copy(
    title = p.title ?: title,
    color = p.color ?: color,
    content = p.content ?: content,
    tidyRanges = p.tidyRanges ?: tidyRanges,
    updatedAt = if (p.title != null || p.content != null) Instant.now().toString() else updatedAt,
)

/** Later fields win. */
fun DocPatch.merge(later: DocPatch) = DocPatch(
    title = later.title ?: title,
    color = later.color ?: color,
    content = later.content ?: content,
    tidyRanges = later.tidyRanges ?: tidyRanges,
)

suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
