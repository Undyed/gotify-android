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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.tinylog.kotlin.Logger

/**
 * Decides which base URL is currently used (public or internal address of the same server).
 *
 * `Settings.url` delegates to [current], so every existing call site (Retrofit, Coil, websocket,
 * UI) follows the switch without changes. Probing is only triggered by failures or network changes,
 * never on the hot path.
 *
 * Every endpoint is canonicalized through [normalizeEndpoint] before it is persisted or used. A
 * malformed URL must never reach Retrofit/OkHttp: `baseUrl()` throws `IllegalArgumentException` for
 * those, which used to kill the process while the app was starting.
 */
internal object EndpointResolver {
    // FORK: fired when the active endpoint changed, so activities can drop their pinned client.
    const val ENDPOINT_CHANGED_BROADCAST = "com.github.gotify.fork.ENDPOINT_CHANGED"

    private const val PROBE_TIMEOUT_SECONDS = 2L
    private const val FAILURE_THRESHOLD = 2
    private const val NETWORK_CHANGE_DEBOUNCE_MS = 1200L

    @Volatile
    private var active: String? = null

    @Volatile
    private var failures = 0

    @Volatile
    private var lastNetworkChangeAt = 0L

    @Volatile
    private var lastWifiPreferenceProbeAt = 0L

    private val probing = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    val current: String?
        // `active` is always canonical: bootstrap(), override() and probeNow() normalize before
        // assigning it.
        get() = active

    fun bootstrap(context: Context) {
        this.context = context.applicationContext
        val ctx = this.context ?: return
        val prefs = ForkPrefs(ctx)
        val primary = normalizeEndpoint(storedPrimary(ctx))
        val backup = normalizeEndpoint(storedBackup(ctx))

        val lastGood = normalizeEndpoint(prefs.lastGoodEndpoint)
            ?.takeIf { it == primary || it == backup }
        if (lastGood == null) {
            // Drop values written by older builds (e.g. an internal address without a scheme).
            prefs.lastGoodEndpoint = null
        }

        val preferBackup = prefs.preferInternalOnWifi && isWifi(ctx)
        active = when {
            lastGood == null -> null
            lastGood == backup && !preferBackup && primary != null -> null
            else -> lastGood
        }
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

    /**
     * Called when the device changes network (Wi-Fi <-> mobile, cable, VPN). Drops the current
     * endpoint immediately so the public address is used again, then probes in the background.
     */
    fun onNetworkChanged() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeAt < NETWORK_CHANGE_DEBOUNCE_MS) return
        lastNetworkChangeAt = now
        lastWifiPreferenceProbeAt = 0L
        active = null
        failures = 0
        ForkPrefs(ctx).lastGoodEndpoint = null
        notifyEndpointChanged(ctx)
        restartService(ctx)
        // Give ConnectivityManager a moment to publish the new default network, otherwise
        // isWifi() can still report the old transport and pick the intranet address again.
        probeAfterNetworkChange()
    }

    fun onRequestSucceeded() {
        failures = 0
        val ctx = context ?: return
        val endpoint = normalizeEndpoint(active) ?: normalizeEndpoint(storedPrimary(ctx))
        if (endpoint != null) {
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

    /**
     * FORK: on Wi-Fi, try the internal address even when the public one is still selected. This
     * runs in the background and only switches after the endpoint actually answered.
     */
    fun probeForCurrentNetwork() {
        val ctx = context ?: return
        if (!ForkPrefs(ctx).preferInternalOnWifi) return
        val backup = normalizeEndpoint(storedBackup(ctx)) ?: return
        if (active == backup) return
        if (!isWifi(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastWifiPreferenceProbeAt < 30_000) return
        lastWifiPreferenceProbeAt = now
        probeAfterNetworkChange()
    }

    private fun probeAfterNetworkChange() {
        if (context == null) return
        Thread({
            try {
                Thread.sleep(700)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            probeNow()
        }, "gotify-endpoint-probe-delayed").apply { isDaemon = true }.start()
    }

    fun probeNow() {
        if (!probing.compareAndSet(false, true)) return
        try {
            val ctx = context ?: return
            val target = probeFirst(ctx)
            // Compare against the effective URL, not just the override. When nothing was
            // overridden, Settings.url falls back to the public address; switching "to" that
            // fallback would only cause a pointless activity reload.
            val effective = active ?: Settings(ctx).url.takeIf { it.isNotBlank() }
            if (target != null && target != effective) {
                Logger.info("EndpointResolver: switching to $target")
                active = target
                ForkPrefs(ctx).lastGoodEndpoint = target
                notifyEndpointChanged(ctx)
                restartService(ctx)
            }
        } catch (e: Exception) {
            Logger.error(e, "Endpoint probe failed")
        } finally {
            probing.set(false)
        }
    }

    /**
     * Selects [url] explicitly. Passing `null` means "automatic" (public address, or the internal
     * one on Wi-Fi). Returns false when a non-null [url] is not a valid http(s) endpoint.
     */
    fun override(url: String?): Boolean {
        val ctx = context ?: return false
        val endpoint = if (url == null) null else normalizeEndpoint(url)
        if (url != null && endpoint == null) {
            Logger.warn { "EndpointResolver: ignoring invalid endpoint '$url'" }
            return false
        }
        active = endpoint
        ForkPrefs(ctx).lastGoodEndpoint = endpoint
        failures = 0
        restartService(ctx)
        return true
    }

    /** Tests [url] on a background thread. The callback is invoked on that thread. */
    fun test(url: String?, callback: (Boolean) -> Unit) {
        val endpoint = normalizeEndpoint(url)
        val ctx = context
        if (endpoint == null || ctx == null) {
            callback(false)
            return
        }
        Thread({
            callback(reachable(endpoint, Settings(ctx).sslSettings()))
        }, "gotify-endpoint-test").apply { isDaemon = true }.start()
    }

    private fun probeFirst(ctx: Context): String? {
        val primary = normalizeEndpoint(storedPrimary(ctx)).orEmpty()
        val backup = normalizeEndpoint(storedBackup(ctx))
        val sslSettings = Settings(ctx).sslSettings()
        val preferBackup = ForkPrefs(ctx).preferInternalOnWifi && isWifi(ctx)
        return endpointOrder(primary, backup, preferBackup)
            .firstOrNull { reachable(it, sslSettings) }
    }

    private fun reachable(baseUrl: String, sslSettings: SSLSettings): Boolean {
        val endpoint = normalizeEndpoint(baseUrl) ?: return false
        return try {
            val client = ApiClient(arrayOf<String>())
            CertUtils.applySslSettings(client.okBuilder, sslSettings)
            client.okBuilder
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
            client.adapterBuilder.baseUrl("$endpoint/")
            Api.execute(client.createService(InfoApi::class.java).version)
            true
        } catch (e: Exception) {
            Logger.info("Endpoint $endpoint not reachable: ${e.message}")
            false
        }
    }

    private fun restartService(ctx: Context) {
        // Never start the service when the selected URL is unusable: its onCreate() builds a
        // Retrofit client and would crash the whole process.
        if (Settings(ctx).url.isBlank()) return
        try {
            ctx.startService(Intent(ctx, WebSocketService::class.java))
        } catch (e: Exception) {
            Logger.error(e, "Could not restart the websocket service")
        }
    }

    private fun isWifi(ctx: Context): Boolean = try {
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        // A VPN hides the underlying Wi-Fi transport on many devices (Clash & co). Treat it as
        // mobile data so the public address is preferred instead of probing the intranet forever.
        capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (e: Exception) {
        false
    }

    private fun notifyEndpointChanged(ctx: Context) {
        try {
            ctx.sendBroadcast(
                Intent(ENDPOINT_CHANGED_BROADCAST).setPackage(ctx.packageName)
            )
        } catch (e: Exception) {
            Logger.error(e, "Could not broadcast the endpoint change")
        }
    }

    private fun storedPrimary(ctx: Context): String =
        ctx.getSharedPreferences("gotify", Context.MODE_PRIVATE).getString("url", "").orEmpty()

    private fun storedBackup(ctx: Context): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("backupUrl", "").orEmpty()
}

/**
 * Canonicalizes a user supplied server address.
 *
 * A scheme is guessed for addresses without one (`192.168.1.10:8080` becomes `http://...`,
 * `gotify.example.com` becomes `https://...`), trailing slashes are removed and only plain
 * http(s) URLs without query/fragment/credentials are accepted. Returns null for unusable input.
 */
internal fun normalizeEndpoint(raw: String?): String? {
    var candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    if (!candidate.contains("://")) {
        candidate = candidate.trimStart('/')
        candidate = guessScheme(candidate) + candidate
    }
    val parsed = candidate.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.host.isBlank()) return null
    if (parsed.query != null || parsed.fragment != null) return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    return parsed.toString().trimEnd('/').takeIf { it.isNotEmpty() }
}

private fun guessScheme(raw: String): String {
    val authority = raw.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        else -> authority.substringBefore(':')
    }.lowercase()
    val isLocal = host == "localhost" ||
        host == "::1" ||
        host == "127.0.0.1" ||
        host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
    return if (isLocal) "http://" else "https://"
}

/**
 * Wi-Fi prefers the internal address, mobile data prefers the public one. Duplicates are removed
 * and invalid values are ignored.
 */
internal fun endpointOrder(primary: String, backup: String?, preferBackup: Boolean): List<String> {
    val endpoints = LinkedHashSet<String>()
    normalizeEndpoint(primary)?.let { endpoints.add(it) }
    normalizeEndpoint(backup)?.let { endpoints.add(it) }
    val ordered = endpoints.toList()
    return if (preferBackup) ordered.reversed() else ordered
}
