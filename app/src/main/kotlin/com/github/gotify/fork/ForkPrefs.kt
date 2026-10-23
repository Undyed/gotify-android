package com.github.gotify.fork

import android.content.Context
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Fork-only preferences. Kept separate from [com.github.gotify.Settings] so that upstream
 * changes to the "gotify" preference file cannot break this feature.
 */
internal class ForkPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastGoodEndpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) {
            prefs.edit().putString(KEY_ENDPOINT, value).apply()
        }

    var lastReceivedMessageId: Long
        get() = prefs.getLong(KEY_LAST_MESSAGE, -1L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_MESSAGE, value).apply()
        }

    fun shouldBackfill(): Boolean {
        val last = prefs.getLong(KEY_LAST_BACKFILL, 0L)
        if (last <= 0L) return true
        return Instant.ofEpochMilli(last)
            .plus(BACKFILL_INTERVAL_HOURS, ChronoUnit.HOURS)
            .isBefore(Instant.now())
    }

    fun markBackfilled() {
        prefs.edit().putLong(KEY_LAST_BACKFILL, System.currentTimeMillis()).apply()
    }

    companion object {
        private const val NAME = "gotify_fork"
        private const val KEY_ENDPOINT = "lastGoodEndpoint"
        private const val KEY_LAST_MESSAGE = "lastReceivedMessageId"
        private const val KEY_LAST_BACKFILL = "lastBackfillAt"
        private const val BACKFILL_INTERVAL_HOURS = 6L
    }
}
