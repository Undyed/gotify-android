package com.github.gotify.fork

import com.github.gotify.api.Api
import com.github.gotify.api.ApiException
import com.github.gotify.client.api.MessageApi
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.tinylog.kotlin.Logger

/**
 * Fills the offline cache with the messages of the last [RETENTION_DAYS] days, so that a fresh
 * install still has something to show when the network is gone. Runs in the background and stops
 * as soon as an error occurs or the retention window is covered.
 */
internal object Backfill {
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 10
    private const val RETENTION_DAYS = 7L

    fun run(api: MessageApi, store: MessageStore) {
        try {
            val cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS)
            var since: Long? = null
            var pages = 0
            while (pages < MAX_PAGES) {
                val paged = Api.execute(api.getMessages(PAGE_SIZE, since))
                val messages = paged.messages
                if (messages.isEmpty()) break
                store.append(messages)
                val oldest = messages.lastOrNull()?.date
                if (oldest != null && oldest.toInstant().isBefore(cutoff)) break
                since = paged.paging?.since
                if (paged.paging?.next == null || since == null) break
                pages++
            }
            Logger.info("Backfill finished after $pages pages")
        } catch (e: ApiException) {
            Logger.info("Backfill stopped with code ${e.code}")
        }
    }
}
