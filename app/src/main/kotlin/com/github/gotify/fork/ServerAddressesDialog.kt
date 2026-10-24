package com.github.gotify.fork

import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.databinding.DialogServerAddressesBinding
import com.github.gotify.service.WebSocketService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.tinylog.kotlin.Logger

/**
 * Editor for the two addresses of the same gotify server.
 *
 * The values are validated before they are written: an invalid address must never reach
 * `ClientFactory`/`WebSocketConnection`, because Retrofit/OkHttp throw for those and the resulting
 * exception kills the app on the next start.
 */
internal object ServerAddressesDialog {

    fun show(activity: FragmentActivity, settings: Settings, onSaved: () -> Unit = {}) {
        val binding = DialogServerAddressesBinding.inflate(activity.layoutInflater)
        binding.publicUrl.setText(
            normalizeEndpoint(settings.storedUrl) ?: settings.storedUrl.trim()
        )
        binding.internalUrl.setText(
            normalizeEndpoint(settings.backupUrl) ?: settings.backupUrl.trim()
        )
        binding.preferInternal.isChecked = ForkPrefs(activity).preferInternalOnWifi
        binding.currentEndpoint.text = activity.getString(
            R.string.fork_server_addresses_current,
            settings.url.ifBlank { activity.getString(R.string.fork_server_addresses_not_set) }
        )

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.fork_server_addresses_title)
            .setView(binding.root)
            .setPositiveButton(R.string.fork_save, null)
            .setNeutralButton(R.string.fork_test, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val testButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)

            saveButton.setOnClickListener {
                val public = normalizeEndpoint(binding.publicUrl.text?.toString())
                val internalRaw = binding.internalUrl.text?.toString()?.trim().orEmpty()
                val internal = if (internalRaw.isEmpty()) {
                    null
                } else {
                    normalizeEndpoint(internalRaw)
                }
                var valid = true
                if (public == null) {
                    binding.publicLayout.error =
                        activity.getString(R.string.fork_server_addresses_invalid)
                    valid = false
                } else {
                    binding.publicLayout.error = null
                }
                if (internalRaw.isNotEmpty() && internal == null) {
                    binding.internalLayout.error =
                        activity.getString(R.string.fork_server_addresses_invalid)
                    valid = false
                } else {
                    binding.internalLayout.error = null
                }
                if (!valid || public == null) return@setOnClickListener

                saveAddresses(
                    activity,
                    settings,
                    public,
                    internal,
                    binding.preferInternal.isChecked
                )
                Toast.makeText(
                    activity,
                    R.string.fork_server_addresses_saved,
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                onSaved()
            }

            testButton.setOnClickListener {
                val public = normalizeEndpoint(binding.publicUrl.text?.toString())
                val internalRaw = binding.internalUrl.text?.toString()?.trim().orEmpty()
                val internal = if (internalRaw.isEmpty()) {
                    null
                } else {
                    normalizeEndpoint(internalRaw)
                }
                if (public == null) {
                    binding.publicLayout.error =
                        activity.getString(R.string.fork_server_addresses_invalid)
                    return@setOnClickListener
                }
                if (internalRaw.isNotEmpty() && internal == null) {
                    binding.internalLayout.error =
                        activity.getString(R.string.fork_server_addresses_invalid)
                    return@setOnClickListener
                }
                testAddresses(activity, binding, testButton, public, internal)
            }
        }
        dialog.show()
    }

    private fun saveAddresses(
        activity: FragmentActivity,
        settings: Settings,
        public: String,
        internal: String?,
        preferInternal: Boolean
    ) {
        // Changing the primary address also invalidates the remembered endpoint.
        settings.url = public
        settings.backupUrl = internal.orEmpty()
        ForkPrefs(activity).preferInternalOnWifi = preferInternal
        EndpointResolver.onPrimaryChanged()
        EndpointResolver.probeAsync()
        try {
            activity.startService(Intent(activity, WebSocketService::class.java))
        } catch (e: Exception) {
            Logger.error(e, "Could not restart the websocket service")
        }
    }

    private fun testAddresses(
        activity: FragmentActivity,
        binding: DialogServerAddressesBinding,
        testButton: android.widget.Button,
        public: String,
        internal: String?
    ) {
        binding.testStatus.visibility = View.VISIBLE
        binding.testStatus.text = activity.getString(R.string.fork_server_addresses_testing)
        testButton.isEnabled = false

        val pending = AtomicInteger(if (internal != null) 2 else 1)
        val lines = Collections.synchronizedList(mutableListOf<String>())

        fun report(label: String, ok: Boolean) {
            lines.add(
                "$label: " + activity.getString(
                    if (ok) {
                        R.string.fork_server_addresses_test_ok
                    } else {
                        R.string.fork_server_addresses_test_failed
                    }
                )
            )
            if (pending.decrementAndGet() != 0) return
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                binding.testStatus.text = lines.joinToString("\n")
                testButton.isEnabled = true
            }
        }

        val publicLabel = activity.getString(R.string.fork_server_addresses_public)
        EndpointResolver.test(public) { ok -> report(publicLabel, ok) }
        if (internal != null) {
            val internalLabel = activity.getString(R.string.fork_server_addresses_internal)
            EndpointResolver.test(internal) { ok -> report(internalLabel, ok) }
        }
    }
}
