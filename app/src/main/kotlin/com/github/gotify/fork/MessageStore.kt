package com.github.gotify.fork

import android.content.Context
import com.github.gotify.Utils
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.Message
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.tinylog.kotlin.Logger

/**
 * Local message cache used for offline mode.
 *
 * The cache is a set of JSON files (one per application) below `filesDir/offline`. Messages are
 * stored as opaque JSON, so changes to the generated `client` models cannot break already cached
 * data. Writes are debounced and atomic (temp file + rename), reads fall back to an empty list when
 * a file is corrupt.
 */
internal class MessageStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    private val lock = Any()
    private val messages = HashMap<Long, MutableList<Message>>()
    private val dirtyApps = HashSet<Long>()
    private var applications: MutableList<Application>? = null
    private var applicationsDirty = false
    private var pending: MutableList<PendingDelete>? = null
    private var pendingDirty = false
    private var flushTask: ScheduledFuture<*>? = null
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gotify-message-store").apply { isDaemon = true }
        }

    init {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    fun load(appId: Long, limit: Int = DEFAULT_LIMIT): List<Message> = synchronized(lock) {
        messagesOf(appId).take(limit).toList()
    }

    fun loadAll(limitPerApp: Int = DEFAULT_LIMIT): List<Message> = synchronized(lock) {
        appIds()
            .flatMap { messagesOf(it).take(limitPerApp) }
            .sortedByDescending { it.id ?: 0L }
    }

    fun append(newMessages: List<Message>) {
        if (newMessages.isEmpty()) return
        synchronized(lock) {
            val byApp = LinkedHashMap<Long, MutableList<Message>>()
            newMessages.forEach { message ->
                val appId = message.appid ?: return@forEach
                val id = message.id ?: return@forEach
                if (id < 0) return@forEach
                byApp.getOrPut(appId) { mutableListOf() }.add(message)
            }
            byApp.forEach { (appId, group) ->
                val cached = messagesOf(appId)
                val known = cached.mapTo(HashSet()) { it.id }
                group.forEach { message ->
                    if (known.add(message.id)) {
                        cached.add(message)
                    }
                }
                cached.sortByDescending { it.id ?: 0L }
                messages[appId] = prune(cached)
                dirtyApps.add(appId)
            }
            scheduleFlush()
        }
    }

    fun remove(appId: Long, messageId: Long) {
        synchronized(lock) {
            if (messagesOf(appId).removeAll { it.id == messageId }) {
                dirtyApps.add(appId)
                scheduleFlush()
            }
        }
    }

    fun clearLocal(appId: Long) {
        synchronized(lock) {
            val targets = if (appId == ALL_APPS) appIds() else setOf(appId)
            targets.forEach {
                messages[it] = mutableListOf()
                dirtyApps.add(it)
            }
            scheduleFlush()
        }
    }

    fun saveApps(apps: List<Application>) {
        synchronized(lock) {
            applications = apps.toMutableList()
            applicationsDirty = true
            scheduleFlush()
        }
    }

    fun loadApps(): List<Application> = synchronized(lock) {
        applications ?: readApplications().also { applications = it }
    }

    fun pendingDeletes(): List<PendingDelete> = synchronized(lock) {
        pendingList().toList()
    }

    fun enqueueDelete(appId: Long, messageId: Long) {
        mutatePending { list -> list.add(PendingDelete(appId, messageId, false)) }
    }

    fun enqueueDeleteAll(appId: Long) {
        mutatePending { list ->
            list.removeAll { it.appId == appId && !it.all }
            list.add(PendingDelete(appId, null, true))
        }
    }

    fun removePending(delete: PendingDelete) {
        mutatePending { list ->
            list.removeAll {
                it.appId == delete.appId && it.messageId == delete.messageId && it.all == delete.all
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            flushTask?.cancel(false)
            flushTask = null
            dirtyApps.toList().forEach { appId ->
                write(fileOf(appId), Utils.JSON.toJson(messages[appId].orEmpty()))
            }
            dirtyApps.clear()
            if (applicationsDirty) {
                write(File(dir, APPS_FILE), Utils.JSON.toJson(applications.orEmpty()))
                applicationsDirty = false
            }
            if (pendingDirty) {
                write(File(dir, PENDING_FILE), Utils.JSON.toJson(pending.orEmpty()))
                pendingDirty = false
            }
        }
    }

    fun shutdown() {
        flush()
        executor.shutdown()
    }

    private fun mutatePending(block: (MutableList<PendingDelete>) -> Unit) {
        synchronized(lock) {
            val list = pendingList()
            block(list)
            while (list.size > MAX_PENDING) {
                list.removeAt(0)
            }
            pendingDirty = true
            scheduleFlush()
        }
    }

    private fun pendingList(): MutableList<PendingDelete> =
        pending ?: readPending().toMutableList().also { pending = it }

    private fun scheduleFlush() {
        if (flushTask != null) return
        flushTask = executor.schedule(
            Runnable { flush() },
            FLUSH_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun messagesOf(appId: Long): MutableList<Message> =
        messages.getOrPut(appId) { readMessages(fileOf(appId)) }

    private fun appIds(): Set<Long> {
        val ids = HashSet<Long>()
        ids.addAll(messages.keys)
        appFiles().forEach { file ->
            val appId = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull()
            if (appId != null) ids.add(appId)
        }
        return ids
    }

    private fun appFiles(): List<File> = dir.listFiles()
        .orEmpty()
        .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }

    private fun fileOf(appId: Long) = File(dir, "$FILE_PREFIX$appId$FILE_SUFFIX")

    /**
     * Keeps the newest [KEEP_NEWEST] messages unconditionally (guards against a wrong device clock)
     * and everything younger than [RETENTION_DAYS], capped at [MAX_MESSAGES_PER_APP].
     */
    private fun prune(source: List<Message>): MutableList<Message> {
        val cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val result = ArrayList<Message>(minOf(source.size, MAX_MESSAGES_PER_APP))
        source.forEachIndexed { index, message ->
            if (result.size >= MAX_MESSAGES_PER_APP) return@forEachIndexed
            val date = message.date
            if (index < KEEP_NEWEST || date == null || date.toInstant().isAfter(cutoff)) {
                result.add(message)
            }
        }
        return result
    }

    private fun readMessages(file: File): MutableList<Message> {
        if (!file.exists()) return mutableListOf()
        val parsed: Array<Message>? = try {
            Utils.JSON.fromJson(file.readText(), Array<Message>::class.java)
        } catch (e: Exception) {
            Logger.error(e) { "Corrupt message cache ${file.name}, removing it" }
            file.delete()
            null
        }
        return parsed?.sortedByDescending { it.id ?: 0L }?.toMutableList() ?: mutableListOf()
    }

    private fun readApplications(): MutableList<Application> {
        val file = File(dir, APPS_FILE)
        if (!file.exists()) return mutableListOf()
        val parsed: Array<Application>? = try {
            Utils.JSON.fromJson(file.readText(), Array<Application>::class.java)
        } catch (e: Exception) {
            Logger.error(e) { "Corrupt application cache, removing it" }
            file.delete()
            null
        }
        return parsed?.toMutableList() ?: mutableListOf()
    }

    private fun readPending(): List<PendingDelete> {
        val file = File(dir, PENDING_FILE)
        if (!file.exists()) return emptyList()
        val parsed: Array<PendingDelete>? = try {
            Utils.JSON.fromJson(file.readText(), Array<PendingDelete>::class.java)
        } catch (e: Exception) {
            Logger.error(e) { "Corrupt pending deletion list, removing it" }
            file.delete()
            null
        }
        return parsed?.toList().orEmpty()
    }

    private fun write(file: File, content: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, file.name + TMP_SUFFIX)
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Logger.error(e) { "Could not persist ${file.name}" }
        }
    }

    companion object {
        const val ALL_APPS = -1L

        @Volatile
        private var instance: MessageStore? = null

        // FORK: one store per process, shared by the activity and the websocket service so that
        // both never write the same files from separate in-memory caches.
        fun get(context: Context): MessageStore = instance ?: synchronized(this) {
            instance ?: MessageStore(context.applicationContext).also { instance = it }
        }

        private const val DIRECTORY = "offline"
        private const val FILE_PREFIX = "messages-"
        private const val FILE_SUFFIX = ".json"
        private const val APPS_FILE = "apps.json"
        private const val PENDING_FILE = "pending.json"
        private const val TMP_SUFFIX = ".tmp"
        private const val RETENTION_DAYS = 7L
        private const val KEEP_NEWEST = 20
        private const val MAX_MESSAGES_PER_APP = 2000
        private const val MAX_PENDING = 500
        private const val DEFAULT_LIMIT = 200
        private const val FLUSH_DELAY_MS = 1000L
    }
}
