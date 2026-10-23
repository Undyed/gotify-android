package com.github.gotify.fork

import com.github.gotify.client.model.Message
import com.github.gotify.messages.provider.MessageRequester
import org.tinylog.kotlin.Logger

/**
 * A deletion that still has to be sent to the server. [messageId] is null for "delete all".
 */
internal class PendingDelete() {
    var appId: Long = 0L
    var messageId: Long? = null
    var all: Boolean = false

    constructor(appId: Long, messageId: Long?, all: Boolean) : this() {
        this.appId = appId
        this.messageId = messageId
        this.all = all
    }
}

/**
 * Deletes are applied locally right away and replayed against the server as soon as it is
 * reachable again, so deleting while offline does not resurrect messages on the next sync.
 */
internal object PendingSync {
    fun deleteNow(store: MessageStore, requester: MessageRequester, message: Message) {
        val appId = message.appid ?: return
        val messageId = message.id ?: return
        store.remove(appId, messageId)
        requester.asyncRemoveMessage(messageId) { success ->
            if (!success) {
                store.enqueueDelete(appId, messageId)
            }
        }
    }

    fun replay(store: MessageStore, requester: MessageRequester) {
        val pending = store.pendingDeletes()
        if (pending.isEmpty()) return

        pending.filter { it.all }.forEach { delete ->
            if (requester.deleteAll(delete.appId)) {
                store.removePending(delete)
            }
        }
        pending.filter { !it.all }.forEach { delete ->
            val messageId = delete.messageId ?: return@forEach
            requester.asyncRemoveMessage(messageId) { success ->
                if (success) {
                    store.removePending(delete)
                }
            }
        }
        Logger.info("Replayed ${pending.size} pending deletions")
    }
}
