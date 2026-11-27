// #167: shares waiting to go out, the worker that sends them (with the app closed, and once the phone is back online),
// and the Direct Share shortcuts that put the agents in the system share sheet.
package dev.supermux.android.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.supermux.android.MainActivity
import dev.supermux.android.applets.AppletWidgetStore
import dev.supermux.android.host.HostStores
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.net.ChunkSource
import dev.supermux.share.ShareItem
import dev.supermux.share.ShareJob
import dev.supermux.share.ShareOutbox
import dev.supermux.share.ShareResult
import dev.supermux.share.ShareSender
import dev.supermux.share.ShareTarget
import dev.supermux.share.ShareTargets
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object ShareQueue {
    private const val TAG = "SupermuxShare"
    private const val CHANNEL = "shares"
    const val SHORTCUT_CATEGORY = "dev.supermux.category.SHARE_TARGET"
    private const val SHORTCUT_PREFIX = "share:"
    private const val MAX_SHORTCUTS = 4
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(context: Context) = File(context.filesDir, "share").apply { mkdirs() }

    private fun outbox(context: Context): ShareOutbox {
        val f = File(dir(context), "outbox.json")
        return ShareOutbox({ f.takeIf { it.exists() }?.readText() }, { text ->
            val tmp = File(f.parentFile, "outbox.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        })
    }

    private fun recentFile(context: Context) = File(dir(context), "recent.json")
    private val recentSer = MapSerializer(String.serializer(), Long.serializer())

    private fun recent(context: Context): Map<String, Long> =
        runCatching { json.decodeFromString(recentSer, recentFile(context).readText()) }.getOrDefault(emptyMap())

    /** Every paired host's chats, Agents first, from the snapshots the app keeps (works with the app closed). */
    fun targets(context: Context): List<ShareTarget> {
        SecureTokenStoreContext.init(context.applicationContext)
        val hosts = runCatching { HostStores.store(context).list() }.getOrDefault(emptyList())
        val snaps = HostStores.snapshotStore(context).all().filter { s -> hosts.any { it.recordId == s.recordId } }
        return ShareTargets.from(snaps, hosts.associate { it.recordId to it.displayName }, recent(context))
    }

    /** The Direct Share shortcut id for a target, and back. */
    fun shortcutId(t: ShareTarget) = "$SHORTCUT_PREFIX${t.hostId}:${t.sessionId}"
    fun fromShortcutId(id: String?, targets: List<ShareTarget>): ShareTarget? =
        id?.removePrefix(SHORTCUT_PREFIX)?.let { key -> targets.firstOrNull { "${it.hostId}:${it.sessionId}" == key } }

    /** Copies what another app shared into our own storage now: its read grant ends with the share sheet. */
    suspend fun copyIn(context: Context, jobId: String, uris: List<Uri>, typeHint: String?): List<ShareItem> = withContext(Dispatchers.IO) {
        val into = File(dir(context), jobId).apply { mkdirs() }
        uris.mapIndexedNotNull { i, uri ->
            runCatching {
                val cr = context.contentResolver
                var name: String? = null
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                        size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { c.getLong(it) } ?: -1L
                    }
                }
                val mime = cr.getType(uri) ?: typeHint?.takeIf { '*' !in it } ?: "application/octet-stream"
                val safe = (name ?: uri.lastPathSegment ?: "file").replace(Regex("[/\\\\:]"), "_").take(120).ifBlank { "file" }
                val out = File(into, "$i-$safe")
                cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                ShareItem(path = out.absolutePath, name = safe, mime = mime, size = if (size > 0) size else out.length())
            }.onFailure { Log.w(TAG, "copy failed: $uri", it) }.getOrNull()
        }
    }

    /** Queues [job] and starts sending it as soon as there is a connection. Returns the work's id. */
    suspend fun enqueue(context: Context, job: ShareJob): java.util.UUID {
        lock.withLock { withContext(Dispatchers.IO) { outbox(context).put(job) } }
        return schedule(context, job.clientMessageId)
    }

    private fun schedule(context: Context, id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE): java.util.UUID {
        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(workDataOf(ShareWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ShareWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("share-$id", policy, req)
        return req.id
    }

    /** At app start: anything still in the outbox (say, the app was updated mid-send) gets its worker back. */
    suspend fun resume(context: Context) {
        val jobs = lock.withLock { withContext(Dispatchers.IO) { outbox(context).all() } }
        jobs.forEach { schedule(context, it.clientMessageId, ExistingWorkPolicy.KEEP) }
    }

    internal suspend fun job(context: Context, id: String): ShareJob? =
        lock.withLock { withContext(Dispatchers.IO) { outbox(context).all().firstOrNull { it.clientMessageId == id } } }

    internal suspend fun update(context: Context, job: ShareJob) = lock.withLock { withContext(Dispatchers.IO) { outbox(context).put(job) } }

    internal suspend fun finish(context: Context, job: ShareJob, sent: Boolean) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                outbox(context).remove(job.clientMessageId)
                File(dir(context), job.clientMessageId).deleteRecursively()
                if (sent) {
                    val next = recent(context) + ("${job.hostId}/${job.sessionId}" to System.currentTimeMillis())
                    recentFile(context).writeText(json.encodeToString(recentSer, next.entries.sortedByDescending { it.value }.take(50).associate { it.key to it.value }))
                }
            }
        }
        if (sent) {
            runCatching { ShortcutManagerCompat.reportShortcutUsed(context, "$SHORTCUT_PREFIX${job.hostId}:${job.sessionId}") }
            publishShortcuts(context)
        }
    }

    /**
     * The agents shown in the system share sheet's Direct Share row: the first few by default and recency, each a
     * long-lived sharing shortcut (a conversation, so Android ranks it by use).
     */
    fun publishShortcuts(context: Context) {
        runCatching {
            val top = targets(context).filter { it.isAgent }.take(MAX_SHORTCUTS)
            val wanted = top.mapIndexed { rank, t ->
                val icon = IconCompat.createWithAdaptiveBitmap(initialsBitmap(t.name))
                ShortcutInfoCompat.Builder(context, shortcutId(t))
                    .setShortLabel(t.name)
                    .setLongLabel(t.name)
                    .setIcon(icon)
                    .setPerson(Person.Builder().setName(t.name).setKey(shortcutId(t)).setIcon(icon).setBot(true).build())
                    .setLongLived(true)
                    .setCategories(setOf(SHORTCUT_CATEGORY))
                    .setRank(rank)
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(SupermuxMessagingService.EXTRA_SESSION_ID, t.sessionId))
                    .build()
            }
            val ours = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith(SHORTCUT_PREFIX) }.map { it.id }
            val stale = ours - wanted.map { it.id }.toSet()
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            wanted.forEach { ShortcutManagerCompat.pushDynamicShortcut(context, it) }
        }.onFailure { Log.w(TAG, "shortcuts failed", it) }
    }

    /** The agent's initial on its colour, full bleed for an adaptive icon (the launcher masks it). */
    private fun initialsBitmap(name: String): Bitmap {
        val px = 216
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (name.hashCode().toLong() and 0xffff).toFloat() % 360f
        c.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.62f)))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = px * 0.30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isUpperCase() }.take(2).joinToString("") { it.take(1) }.ifEmpty { name.take(1).uppercase() }
        c.drawText(initials, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    internal fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, localized(Str.share_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    internal fun notification(context: Context, text: String, sessionId: String?): android.app.Notification {
        ensureChannel(context)
        val open = sessionId?.let {
            PendingIntent.getActivity(
                context, it.hashCode(),
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(SupermuxMessagingService.EXTRA_SESSION_ID, it),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    internal fun hostApi(context: Context, hostId: String, http: HttpClient) = AppletWidgetStore.hostApi(context, hostId, http)

    internal fun fileSource(item: ShareItem): ChunkSource? {
        val f = item.path?.let(::File)?.takeIf { it.isFile } ?: return null
        return object : ChunkSource {
            override val size: Long = f.length()
            override fun read(offset: Long, len: Int): ByteArray = RandomAccessFile(f, "r").use { raf ->
                val n = minOf(len.toLong(), size - offset).coerceAtLeast(0).toInt()
                ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
            }
        }
    }
}

/** Sends one queued share. Retries (with backoff) while the host is unreachable; drops it if the host refuses it. */
class ShareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_ID = "id"
        const val KEY_ERROR = "error"
        const val TAG = "share"
        private const val MAX_ATTEMPTS = 20
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID).orEmpty()
        val name = ShareQueue.job(applicationContext, id)?.targetName.orEmpty()
        val n = ShareQueue.notification(applicationContext, localized(Str.share_notify_sending, name), null)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(), n)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val job = ShareQueue.job(ctx, id) ?: return Result.success()
        return HttpClient(CIO).use { http ->
            val api = ShareQueue.hostApi(ctx, job.hostId, http)
            if (api == null) {
                ShareQueue.finish(ctx, job, sent = false)
                return@use Result.failure(workDataOf(KEY_ERROR to "not paired"))
            }
            when (val r = ShareSender.send(api, job, ShareQueue::fileSource)) {
                ShareResult.Sent -> {
                    ShareQueue.finish(ctx, job, sent = true)
                    Result.success()
                }
                is ShareResult.Failed -> {
                    ShareQueue.finish(ctx, job, sent = false)
                    notifyFailed(job, r.reason)
                    Result.failure(workDataOf(KEY_ERROR to r.reason))
                }
                is ShareResult.Retry -> {
                    if (r.job.attempts >= MAX_ATTEMPTS) {
                        ShareQueue.finish(ctx, job, sent = false)
                        notifyFailed(job, r.reason)
                        Result.failure(workDataOf(KEY_ERROR to r.reason))
                    } else {
                        ShareQueue.update(ctx, r.job)
                        Result.retry()
                    }
                }
            }
        }
    }

    /** Only the share sheet's own bar says "sent"; a failure after it closed needs a notification. */
    private fun notifyFailed(job: ShareJob, reason: String) {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(job.clientMessageId.hashCode(), ShareQueue.notification(applicationContext, localized(Str.share_failed, job.targetName, reason), job.sessionId))
        }
    }
}
