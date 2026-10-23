package com.github.gotify.fork

import com.github.gotify.client.model.Message
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MessageStoreTest {

    private fun message(
        id: Long,
        appId: Long = 1L,
        date: OffsetDateTime = OffsetDateTime.now()
    ): Message = Message(appId, date, id)

    private fun tempDir(): File = Files.createTempDirectory("gotify-store").toFile()

    @Test
    fun `messages are returned newest first`() {
        val store = MessageStore(tempDir())
        store.append(listOf(message(1), message(3), message(2)))
        store.flush()

        assertEquals(listOf(3L, 2L, 1L), store.load(1).map { it.id })
        store.shutdown()
    }

    @Test
    fun `messages are stored per application`() {
        val store = MessageStore(tempDir())
        store.append(listOf(message(1, appId = 5), message(2, appId = 6)))
        store.flush()

        assertEquals(listOf(1L), store.load(5).map { it.id })
        assertEquals(listOf(2L), store.load(6).map { it.id })
        assertEquals(2, store.loadAll().size)
        store.shutdown()
    }

    @Test
    fun `duplicate ids are stored once`() {
        val store = MessageStore(tempDir())
        store.append(listOf(message(1), message(1)))
        store.flush()

        assertEquals(1, store.load(1).size)
        store.shutdown()
    }

    @Test
    fun `messages older than the retention window are dropped`() {
        val now = OffsetDateTime.now()
        val old = now.minusDays(8)
        val messages = (1L..21L).map { id ->
            if (id == 1L) message(id, 1L, old) else message(id, 1L, now)
        }
        val store = MessageStore(tempDir())
        store.append(messages)
        store.flush()

        val loaded = store.load(1, 100)
        assertEquals(20, loaded.size)
        assertFalse(loaded.any { it.id == 1L })
        store.shutdown()
    }

    @Test
    fun `pending deletions survive a reload`() {
        val dir = tempDir()
        val store = MessageStore(dir)
        store.enqueueDelete(1L, 42L)
        store.enqueueDeleteAll(2L)
        store.flush()
        store.shutdown()

        val reopened = MessageStore(dir)
        val pending = reopened.pendingDeletes()
        assertEquals(2, pending.size)
        assertTrue(pending.any { !it.all && it.messageId == 42L })
        assertTrue(pending.any { it.all && it.appId == 2L })
        reopened.shutdown()
    }

    @Test
    fun `a corrupt cache file is discarded`() {
        val dir = tempDir()
        val file = File(dir, "messages-7.json")
        file.writeText("{ not json")

        val store = MessageStore(dir)
        assertTrue(store.load(7).isEmpty())
        assertFalse(file.exists())
        store.shutdown()
    }
}
