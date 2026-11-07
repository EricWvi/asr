package top.onlyquant.tingxie.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class SendState { Sending, Sent, Failed }

/** One thing sent to a Mac; [id] is when it was sent. [to] is null while no Mac is known; [at] is when it arrived. */
@Serializable
data class SendRecord(
    val id: Long,
    val text: String,
    val state: SendState,
    val to: String? = null,
    val at: Long = 0,
    val error: String = "",
)

/** Records older than this are dropped, however many there are. */
const val KeepSendsMs = 30L * 24 * 60 * 60 * 1000

/** The records still within [KeepSendsMs] of [now]. */
fun List<SendRecord>.recent(now: Long) = filter { now - it.id < KeepSendsMs }

/** A send the process did not live to see the end of may or may not have arrived. */
fun List<SendRecord>.restored() = map {
    if (it.state == SendState.Sending) it.copy(state = SendState.Failed, error = "发送中断，文字已保留") else it
}

/** The red line under a failed record. */
fun sendFailure(e: Throwable): String = when {
    // No Mac to send to: 没有在线的电脑, 这台电脑不在线.
    e is ApiError && e.status == 409 -> "${e.message}，文字已保留"
    else -> e.userMessage()
}

/** Who 自动 will reach, as push.Hub picks: the online Mac used most recently. */
fun autoTarget(macs: List<Device>?): Device? = macs?.filter { it.online }?.maxByOrNull { it.lastActiveAt }

/**
 * The send-to-computer records and target. Records live in a file so they
 * survive restarts (the server keeps nothing), and sends run here rather than
 * on the screen, so leaving the page does not lose one in flight.
 */
class Sends(private val api: Api, private val file: File, private val prefs: Prefs, parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext.job)
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val writing = Mutex()
    private var lastId = 0L

    /** Oldest first; the page shows them the other way round. */
    val records = mutableStateListOf<SendRecord>()

    /** The chosen Mac's id; 0 is 自动. */
    var target by mutableStateOf(0L)
        private set

    /** Every Mac signed in, online ones first; null until first read. */
    var macs by mutableStateOf<List<Device>?>(null)
        private set

    /** Why [macs] could not be read, while it is still unknown. */
    var macsError by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            target = prefs.sendTarget()
            val saved = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<List<SendRecord>>(file.readText()) }.getOrNull()
            }
            if (saved != null) {
                val kept = saved.restored().recent(System.currentTimeMillis())
                records.addAll(0, kept)
                if (kept.size < saved.size) save()
            }
        }
    }

    val chosen: Device? get() = macs?.find { it.id == target }

    /** The Mac a send is expected to reach; the server has the last word. */
    val expected: Device? get() = if (target == 0L) autoTarget(macs) else chosen

    fun choose(id: Long) {
        target = id
        scope.launch { prefs.setSendTarget(id) }
    }

    /** Re-reads the Macs; a chosen one that was signed out falls back to 自动. */
    suspend fun refreshMacs() {
        try {
            val list = api.devices().filter { it.platform == "mac" }
            macs = list
            macsError = null
            if (target != 0L && list.none { it.id == target }) choose(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (macs == null) macsError = e.userMessage()
        }
    }

    /** Sends [text] to the target; [replacing] is a failed record this one retries. */
    fun send(text: String, replacing: Long? = null) {
        val id = maxOf(System.currentTimeMillis(), lastId + 1).also { lastId = it }
        val to = target
        if (replacing != null) records.removeAll { it.id == replacing }
        records.add(SendRecord(id, text, SendState.Sending, expected?.name))
        prune()
        save()
        scope.launch {
            try {
                val device = api.send(text, to)
                update(id) { it.copy(state = SendState.Sent, to = device.name, at = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(state = SendState.Failed, error = sendFailure(e)) }
                refreshMacs()
            }
        }
    }

    /** Drops the records past 30 days; also run when the page opens, as the app may stay up for days. */
    fun prune() {
        val now = System.currentTimeMillis()
        if (records.all { now - it.id < KeepSendsMs }) return
        records.retainAll { now - it.id < KeepSendsMs }
        save()
    }

    private fun update(id: Long, change: (SendRecord) -> SendRecord) {
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return
        records[i] = change(records[i])
        save()
    }

    private fun save() {
        val snapshot = records.toList()
        scope.launch {
            writing.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(file.path + ".tmp")
                        tmp.writeText(json.encodeToString(snapshot))
                        tmp.renameTo(file)
                    }
                }
            }
        }
    }

    /** Signing out: the records go with the account. */
    suspend fun clear() {
        job.cancelAndJoin()
        records.clear()
        withContext(Dispatchers.IO) { file.delete() }
    }
}
