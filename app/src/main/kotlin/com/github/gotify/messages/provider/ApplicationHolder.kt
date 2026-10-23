package com.github.gotify.messages.provider

import android.app.Activity
import com.github.gotify.Utils
import com.github.gotify.api.Callback
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.ApplicationApi
import com.github.gotify.client.model.Application
import com.github.gotify.fork.MessageStore

internal class ApplicationHolder(
    private val activity: Activity,
    private val client: ApiClient,
    private val store: MessageStore? = null
) {
    private var state = listOf<Application>()
    private var onUpdate: Runnable? = null
    private var onUpdateFailed: Runnable? = null

    fun wasRequested() = state.isNotEmpty()

    fun request() {
        client.createService(ApplicationApi::class.java)
            .apps
            .enqueue(
                Callback.callInUI(
                    activity,
                    onSuccess = Callback.SuccessBody { apps -> onReceiveApps(apps) },
                    onError = { onFailedApps() }
                )
            )
    }

    private fun onReceiveApps(apps: List<Application>) {
        state = apps
        store?.saveApps(apps) // FORK: keep the list for offline use
        if (onUpdate != null) onUpdate!!.run()
    }

    private fun onFailedApps() {
        val cached = store?.loadApps().orEmpty() // FORK: fall back to the offline cache
        if (cached.isNotEmpty()) {
            state = cached
            if (onUpdate != null) onUpdate!!.run()
            return
        }
        Utils.showSnackBar(activity, "Could not request applications, see logs.")
        if (onUpdateFailed != null) onUpdateFailed!!.run()
    }

    fun get() = state

    fun onUpdate(runnable: Runnable?) {
        onUpdate = runnable
    }

    fun onUpdateFailed(runnable: Runnable?) {
        onUpdateFailed = runnable
    }
}
