package com.github.gotify.messages.provider

import com.github.gotify.client.model.Message

internal class MessageState {
    var appId = 0L
    var loaded = false
    var hasNext = false
    var fromCache = false // FORK: hydrated from the offline cache, a network refresh is pending
    var nextSince = 0L
    var messages = mutableListOf<Message>()

    companion object {
        const val ALL_MESSAGES = -1L
    }
}
