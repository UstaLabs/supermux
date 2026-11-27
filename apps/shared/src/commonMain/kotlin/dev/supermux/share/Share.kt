// #167: sharing from another app (the phone's share sheet) straight to an agent, with a message.
package dev.supermux.share

import dev.supermux.host.HostSnapshot
import dev.supermux.net.BrokerApi
import dev.supermux.net.ChunkSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A chat the share sheet offers: an Agent, or (on the second tab) any other live session. */
@Serializable
data class ShareTarget(
    val hostId: String,
    val hostName: String,
    val sessionId: String,
    val name: String,
    val isAgent: Boolean,
    val isDefault: Boolean = false,
    /** The Agent's avatar content hash, when it has one. */
    val avatar: String? = null,
    /** The newest message's first line, for the row's second line. */
    val preview: String? = null,
    /** Epoch millis of the newest message (or of a share to it), for "recent first". */
    val lastAt: Long = 0L,
)

object ShareTargets {
    private const val AGENT_ROLE = "personal_assistant"

    /**
     * Every paired host's chats from its offline snapshot, Agents first: the default Agent, then the most recent
     * (by its newest message or the last share to it, [recentShares] keyed "host/session" → epoch millis).
     */
    fun from(
        snapshots: List<HostSnapshot>,
        hostNames: Map<String, String>,
        recentShares: Map<String, Long> = emptyMap(),
    ): List<ShareTarget> = snapshots.flatMap { snap ->
        snap.sessions.filter { it.id.isNotEmpty() }.map { s ->
            val last = snap.lastMessages[s.id]
            val lastAt = maxOf(parseIsoMillis(last?.ts) ?: 0L, recentShares["${snap.recordId}/${s.id}"] ?: 0L)
            ShareTarget(
                hostId = snap.recordId,
                hostName = hostNames[snap.recordId] ?: snap.recordId,
                sessionId = s.id,
                name = s.name,
                isAgent = s.role == AGENT_ROLE,
                isDefault = s.isDefault == true,
                avatar = s.avatar,
                preview = last?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(120),
                lastAt = lastAt,
            )
        }
    }.sortedWith(compareByDescending<ShareTarget> { it.isAgent }.thenByDescending { it.isDefault }.thenByDescending { it.lastAt }.thenBy { it.name.lowercase() })

    /** What the sheet lists for a tab, a host filter (null = all) and a search. */
    fun filter(all: List<ShareTarget>, agents: Boolean, hostId: String?, query: String): List<ShareTarget> {
        val q = query.trim().lowercase()
        return all.filter { it.isAgent == agents && (hostId == null || it.hostId == hostId) && (q.isEmpty() || q in it.name.lowercase()) }
    }

    /** "2026-11-27T12:00:00.000Z" → epoch millis; null when it isn't one. */
    internal fun parseIsoMillis(ts: String?): Long? = ts?.let { dev.supermux.chat.parseIso8601Millis(it) }
}

/** One thing shared: words or a link (sent inside the message), or a file copied into the app's own storage. */
@Serializable
data class ShareItem(
    val text: String? = null,
    /** A file the app owns (copied out of the sharing app at once: its read grant dies with the share). */
    val path: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Long = 0L,
    /** Set once uploaded, so a retry never uploads it again. */
    val fileId: String? = null,
)

/** A share waiting to go out. [clientMessageId] makes the broker accept it once however often it's retried. */
@Serializable
data class ShareJob(
    val clientMessageId: String,
    val hostId: String,
    val sessionId: String,
    val targetName: String,
    val message: String = "",
    val items: List<ShareItem> = emptyList(),
    val createdAt: Long = 0L,
    val attempts: Int = 0,
) {
    /** The message the agent reads: the user's words, then each shared text or link on its own line. */
    fun text(): String = (listOf(message.trim()) + items.mapNotNull { it.text?.trim() }).filter { it.isNotEmpty() }.distinct().joinToString("\n\n")

    val files: List<ShareItem> get() = items.filter { it.path != null }

    companion object {
        fun newId(nowMs: Long, random: kotlin.random.Random = kotlin.random.Random.Default): String =
            "web-share-$nowMs-" + (1..10).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
    }
}

/** The queued shares, persisted as one JSON list by the platform ([load]/[save]). Not thread-safe: callers serialize. */
class ShareOutbox(private val load: () -> String?, private val save: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(ShareJob.serializer())

    fun all(): List<ShareJob> = load()?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    fun put(job: ShareJob) = write(all().filter { it.clientMessageId != job.clientMessageId } + job)

    fun remove(clientMessageId: String) = write(all().filter { it.clientMessageId != clientMessageId })

    private fun write(jobs: List<ShareJob>) = save(json.encodeToString(ser, jobs))
}

sealed interface ShareResult {
    data object Sent : ShareResult
    /** Offline, or the host didn't answer: keep it and try again later. */
    data class Retry(val job: ShareJob, val reason: String) : ShareResult
    /** The host refused it for good (the chat is gone, a file it won't take): drop it and say why. */
    data class Failed(val reason: String) : ShareResult
}

object ShareSender {
    /**
     * Uploads [job]'s files that aren't up yet, then posts the message. [open] gives a file's bytes; [onProgress]
     * reports the job's whole upload. Every uploaded file id is kept in the returned [ShareResult.Retry]'s job, so
     * a retry resumes after the last file that made it.
     */
    suspend fun send(
        api: BrokerApi,
        job: ShareJob,
        open: (ShareItem) -> ChunkSource?,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): ShareResult {
        var current = job.copy(attempts = job.attempts + 1)
        val total = current.files.sumOf { it.size }
        var done = current.files.filter { it.fileId != null }.sumOf { it.size }
        try {
            val items = current.items.toMutableList()
            for ((i, item) in items.withIndex()) {
                if (item.path == null || item.fileId != null) continue
                val src = open(item) ?: return ShareResult.Failed("${item.name ?: "A file"} is no longer there")
                val base = done
                val up = api.uploadResumable(current.sessionId, src, item.name ?: "file", item.mime ?: "application/octet-stream") { s, _ -> onProgress(base + s, total) }
                items[i] = item.copy(fileId = up.file_id)
                current = current.copy(items = items.toList())
                done += item.size
            }
            when (val r = api.sendShare(current.sessionId, current.clientMessageId, current.text(), current.files.mapNotNull { it.fileId })) {
                is BrokerApi.ShareSendOutcome.Ok -> return ShareResult.Sent
                is BrokerApi.ShareSendOutcome.Refused -> return if (r.status in 500..599 || r.status == 408 || r.status == 429) ShareResult.Retry(current, r.error) else ShareResult.Failed(r.error)
            }
        } catch (c: CancellationException) {
            // BrokerApi reports an unreachable host or a refusal as a cancellation naming it; a real cancel rethrows.
            val why = c.message ?: throw c
            if (!why.startsWith(BrokerApi.UNAVAILABLE_PREFIX)) throw c
            val status = Regex("HTTP (\\d{3})").find(why)?.groupValues?.get(1)?.toIntOrNull()
            return if (status != null && status in 400..499 && status != 408 && status != 429) ShareResult.Failed(why.substringAfter("HTTP $status").trim().ifEmpty { "HTTP $status" })
            else ShareResult.Retry(current, why.removePrefix(BrokerApi.UNAVAILABLE_PREFIX).trimStart(':', ' '))
        } catch (t: Throwable) {
            return ShareResult.Retry(current, t.message ?: t::class.simpleName ?: "error")
        }
    }
}
