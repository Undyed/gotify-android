package com.github.gotify.fork

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.github.gotify.SSLSettings
import com.github.gotify.Settings
import com.github.gotify.api.Api
import com.github.gotify.api.CertUtils
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.InfoApi
import com.github.gotify.service.WebSocketService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.tinylog.kotlin.Logger

/**
 * Decides which base URL is currently used (public or internal address of the same server).
 *
 * `Settings.url` delegates to [current], so every existing call site (Retrofit, Coil, websocket,
 * UI) follows the switch without changes. Probing is only triggered by failures or network changes,
 * never on the hot path.
 */
internal object EndpointResolver {
    private const val PROBE_TIMEOUT_SECONDS = 2L
    private const val FAILURE_THRESHOLD = 2

    @Volatile
    private var active: String? = null

    @Volatile
    private var failures = 0

    private val probing = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    val current: String?
        get() = active

    fun bootstrap(context: Context) {
        this.context = context.applicationContext
        val ctx = this.context!!
        val lastGood = ForkPrefs(ctx).lastGoodEndpoint
        val backup = storedBackup(ctx)
        // The internal address is only useful on the local network. Keeping it on mobile data
        // would make the first request wait for the connect timeout.
        active = if (lastGood != null && (lastGood != backup || isWifi(ctx))) lastGood else null
        Logger.info("EndpointResolver: started with $active")
    }

    fun onPrimaryChanged() {
        active = null
        failures = 0
        context?.let { ForkPrefs(it).lastGoodEndpoint = null }
    }

    fun reset() {
        onPrimaryChanged()
    }

    fun onRequestSucceeded() {
        failures = 0
        val ctx = context ?: return
        val endpoint = active ?: storedPrimary(ctx)
        if (endpoint.isNotBlank()) {
            val prefs = ForkPrefs(ctx)
            if (prefs.lastGoodEndpoint != endpoint) {
                prefs.lastGoodEndpoint = endpoint
            }
        }
    }

    fun onRequestFailed() {
        failures++
        if (failures >= FAILURE_THRESHOLD) {
            failures = 0
            probeAsync()
        }
    }

    fun probeAsync() {
        if (context == null) return
        Thread({ probeNow() }, "gotify-endpoint-probe").apply { isDaemon = true }.start()
    }

    fun probeNow() {
        if (!probing.compareAndSet(false, true)) return
        try {
            val ctx = context ?: return
            val target = probeFirst(ctx)
            if (target != null && target != active) {
                Logger.info("EndpointResolver: switching to $target")
                active = target
                ForkPrefs(ctx).lastGoodEndpoint = target
                restartService(ctx)
            }
        } catch (e: Exception) {
            Logger.error(e, "Endpoint probe failed")
        } finally {
            probing.set(false)
        }
    }

    fun override(url: String?) {
        val ctx = context ?: return
        active = url
        ForkPrefs(ctx).lastGoodEndpoint = url
        failures = 0
        restartService(ctx)
    }

    private fun probeFirst(ctx: Context): String? {
        val primary = storedPrimary(ctx)
        val backup = storedBackup(ctx)
        if (primary.isBlank()) return null
        val sslSettings = Settings(ctx).sslSettings()
        return endpointOrder(primary, backup, isWifi(ctx))
            .firstOrNull { reachable(it, sslSettings) }
    }

    private fun reachable(baseUrl: String, sslSettings: SSLSettings): Boolean {
        return try {
            val client = ApiClient(arrayOf<String>())
            CertUtils.applySslSettings(client.okBuilder, sslSettings)
            client.okBuilder
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
            client.adapterBuilder.baseUrl("$baseUrl/")
            Api.execute(client.createService(InfoApi::class.java).version)
            true
        } catch (e: Exception) {
            Logger.info("Endpoint $baseUrl not reachable: ${e.message}")
            false
        }
    }

    private fun restartService(ctx: Context) {
        try {
            ctx.startService(Intent(ctx, WebSocketService::class.java))
        } catch (e: Exception) {
            Logger.error(e, "Could not restart the websocket service")
        }
    }

    private fun isWifi(ctx: Context): Boolean = try {
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        false
    }

    private fun storedPrimary(ctx: Context): String =
        ctx.getSharedPreferences("gotify", Context.MODE_PRIVATE).getString("url", "").orEmpty()

    private fun storedBackup(ctx: Context): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("backupUrl", "").orEmpty()
}

/**
 * Wi-Fi prefers the internal address, mobile data prefers the public one. Duplicates are removed.
 */
internal fun endpointOrder(primary: String, backup: String?, preferBackup: Boolean): List<String> {
    val endpoints = LinkedHashSet<String>()
    primary.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
    backup?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
    val ordered = endpoints.toList()
    return if (preferBackup) ordered.reversed() else ordered
}
