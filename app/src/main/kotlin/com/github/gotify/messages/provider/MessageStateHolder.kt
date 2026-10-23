package com.github.gotify.messages.provider

import com.github.gotify.client.model.Message
import com.github.gotify.client.model.PagedMessages
import kotlin.math.max
import org.tinylog.kotlin.Logger

internal class MessageStateHolder {
    @get:Synchronized
    var lastReceivedMessage = -1L
        private set
    private var states = mutableMapOf<Long, MessageState>()
    private var pendingDeletion: MessageDeletion? = null

    @Synchronized
    fun clear() {
        states = mutableMapOf()
    }

    @Synchronized
    fun newMessages(appId: Long, pagedMessages: PagedMessages) {
        val state = state(appId)

        if (!state.loaded && pagedMessages.messages.size > 0) {
            lastReceivedMessage = max(pagedMessages.messages[0].id, lastReceivedMessage)
        }

        state.apply {
            loaded = true
            fromCache = false // FORK
            // FORK: skip messages we already have, e.g. after hydrating from the offline cache
            val known = messages.mapTo(HashSet()) { it.id }
            messages.addAll(pagedMessages.messages.filterNot { it.id in known })
            hasNext = pagedMessages.paging.next != null
            nextSince = pagedMessages.paging.since
            this.appId = appId
        }
        states[appId] = state

        // If there is a message with pending deletion, it should not reappear in the list in case
        // it is added again.
        if (deletionPending()) {
            deleteMessage(pendingDeletion!!.message)
        }
    }

    // FORK: restore messages from the offline cache
    @Synchronized
    fun hydrate(appId: Long, messages: List<Message>) {
        val state = state(appId)
        if (state.loaded) return
        state.apply {
            loaded = true
            hasNext = false
            fromCache = true
            nextSince = messages.lastOrNull()?.id ?: 0L
            this.messages.addAll(messages)
            this.appId = appId
        }
        states[appId] = state
        val newest = messages.firstOrNull()?.id
        if (newest != null && newest > lastReceivedMessage) {
            lastReceivedMessage = newest
        }
    }

    @Synchronized
    fun newMessage(message: Message) {
        if (lastReceivedMessage >= message.id) {
            Logger.warn {
                "Skipping processing message with id ${message.id} as it's already processed (lastReceivedMessage: $lastReceivedMessage)"
            }
            return
        }
        // If there is a message with pending deletion, its indices are going to change. To keep
        // them consistent the deletion is undone first and redone again after adding the new
        // message.
        val deletion = undoPendingDeletion()
        addMessage(message, 0, 0)
        lastReceivedMessage = message.id
        if (deletion != null) deleteMessage(deletion.message)
    }

    @Synchronized
    fun state(appId: Long): MessageState = states[appId] ?: emptyState(appId)

    @Synchronized
    fun deleteAll(appId: Long) {
        clear()
        val state = state(appId)
        state.loaded = true
        states[appId] = state
    }

    private fun emptyState(appId: Long): MessageState {
        return MessageState().apply {
            loaded = false
            hasNext = false
            nextSince = 0
            this.appId = appId
        }
    }

    @Synchronized
    fun deleteMessage(message: Message) {
        val allMessages = state(MessageState.ALL_MESSAGES)
        val appMessages = state(message.appid)
        var pendingDeletedAllPosition = -1
        var pendingDeletedAppPosition = -1

        if (allMessages.loaded) {
            val allPosition = allMessages.messages.indexOf(message)
            if (allPosition != -1) allMessages.messages.removeAt(allPosition)
            pendingDeletedAllPosition = allPosition
        }
        if (appMessages.loaded) {
            val appPosition = appMessages.messages.indexOf(message)
            if (appPosition != -1) appMessages.messages.removeAt(appPosition)
            pendingDeletedAppPosition = appPosition
        }
        pendingDeletion = MessageDeletion(
            message,
            pendingDeletedAllPosition,
            pendingDeletedAppPosition
        )
    }

    @Synchronized
    fun undoPendingDeletion(): MessageDeletion? {
        if (pendingDeletion != null) {
            addMessage(
                pendingDeletion!!.message,
                pendingDeletion!!.allPosition,
                pendingDeletion!!.appPosition
            )
        }
        return purgePendingDeletion()
    }

    @Synchronized
    fun purgePendingDeletion(): MessageDeletion? {
        val result = pendingDeletion
        pendingDeletion = null
        return result
    }

    @Synchronized
    fun deletionPending(): Boolean = pendingDeletion != null

    private fun addMessage(message: Message, allPosition: Int, appPosition: Int) {
        val allMessages = state(MessageState.ALL_MESSAGES)
        val appMessages = state(message.appid)

        if (allMessages.loaded && allPosition != -1) {
            allMessages.messages.add(allPosition, message)
        }
        if (appMessages.loaded && appPosition != -1) {
            appMessages.messages.add(appPosition, message)
        }
    }
}
