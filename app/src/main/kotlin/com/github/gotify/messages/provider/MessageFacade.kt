package com.github.gotify.messages.provider

import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Message
import com.github.gotify.fork.EndpointResolver
import com.github.gotify.fork.MessageStore
import com.github.gotify.fork.PendingSync
import java.util.concurrent.ConcurrentHashMap

internal class MessageFacade(
    api: MessageApi,
    private val applicationHolder: ApplicationHolder,
    private val store: MessageStore? = null
) {
    private val requester = MessageRequester(api)
    private val state = MessageStateHolder()

    // FORK: network loads run outside the monitor; this only prevents duplicate requests.
    private val loadingApps = ConcurrentHashMap.newKeySet<Long>()

    // FORK: true when the last network load failed and the list shows cached data
    @Volatile
    var offline = false
        private set

    @Synchronized
    operator fun get(appId: Long): List<MessageWithImage> {
        return MessageImageCombiner.combine(state.state(appId).messages, applicationHolder.get())
    }

    // FORK: no monitor here. store.append() can read a cache file from disk and must never block
    // get()/canLoadMore() which are called from the UI thread.
    fun addMessages(messages: List<Message>) {
        // FORK: keep messages the service did not persist itself, e.g. missed messages fetched by
        // the activity while the websocket was still reconnecting.
        store?.append(messages)
        messages.forEach {
            state.newMessage(it)
        }
    }

    // FORK: the network call deliberately runs without holding a lock. Previously loadMore() was
    // @Synchronized, so canLoadMore()/get() on the main thread blocked for the whole HTTP timeout
    // and the first screen appeared to freeze.
    fun loadMore(appId: Long): List<MessageWithImage> {
        val messageState = state.state(appId)
        if (!messageState.hasNext && messageState.loaded && !messageState.fromCache) {
            return get(appId)
        }
        if (!loadingApps.add(appId)) {
            return get(appId)
        }
        try {
            val pagedMessages = requester.loadMore(messageState)
            if (pagedMessages != null) {
                state.newMessages(appId, pagedMessages)
                offline = false
                EndpointResolver.onRequestSucceeded() // FORK: remember the working endpoint
                store?.let {
                    it.append(pagedMessages.messages)
                    // replay outside the hot path, this may perform network calls
                    Thread { PendingSync.replay(it, requester) }.start()
                }
            } else {
                offline = true
                EndpointResolver.onRequestFailed() // FORK: maybe the other address is reachable
                hydrate(appId)
            }
        } finally {
            loadingApps.remove(appId)
        }
        return get(appId)
    }

    fun loadMoreIfNotPresent(appId: Long) {
        val messageState = state.state(appId)
        if (!messageState.loaded || messageState.fromCache) {
            loadMore(appId)
        }
    }

    @Synchronized
    fun clear() {
        state.clear()
    }

    fun getLastReceivedMessage(): Long = state.lastReceivedMessage

    @Synchronized
    fun deleteLocal(message: Message) {
        // If there is already a deletion pending, that one should be executed before scheduling the
        // next deletion.
        if (state.deletionPending()) commitDelete()
        state.deleteMessage(message)
    }

    @Synchronized
    fun commitDelete() {
        if (state.deletionPending()) {
            val deletion = state.purgePendingDeletion() ?: return
            val cache = store
            if (cache == null) {
                requester.asyncRemoveMessage(deletion.message.id ?: 0L)
            } else {
                PendingSync.deleteNow(cache, requester, deletion.message)
            }
        }
    }

    @Synchronized
    fun undoDeleteLocal(): MessageDeletion? = state.undoPendingDeletion()

    // FORK: no monitor here either, deleteAll() blocks on the network.
    fun deleteAll(appId: Long): Boolean {
        val success = requester.deleteAll(appId)
        state.deleteAll(appId)
        store?.let { cache ->
            // Always clear locally: when offline the deletion is queued and replayed later.
            cache.clearLocal(appId)
            if (!success) {
                cache.enqueueDeleteAll(appId)
            }
        }
        return success || store != null
    }

    @Synchronized
    fun canLoadMore(appId: Long): Boolean = state.state(appId).hasNext

    // FORK: true once the list (cache or network) has been loaded for this view.
    fun isLoaded(appId: Long): Boolean = state.state(appId).loaded

    // FORK: show cached messages immediately, without waiting for a network round trip.
    fun hydrateFromCache(appId: Long): List<MessageWithImage> {
        if (!state.state(appId).loaded) {
            hydrate(appId)
        }
        return get(appId)
    }

    // FORK: show cached messages while offline
    private fun hydrate(appId: Long) {
        val cache = store ?: return
        val cached = if (appId == MessageState.ALL_MESSAGES) {
            cache.loadAll()
        } else {
            cache.load(appId)
        }
        if (cached.isEmpty()) return
        state.hydrate(appId, cached)
    }
}
