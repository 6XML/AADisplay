package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort join of the accessory hotspot after iAP2 `0x5703` / `0x4301`.
 * Callers wait for [ensureAccessoryWifi] before dialing AirPlay TCP.
 */
class CarPlayWifiHandoff(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val pendingReady = CopyOnWriteArrayList<() -> Unit>()
    private val pendingSsid = AtomicReference<String?>(null)
    private val readyFired = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun requestJoin(config: Iap2Csm.WifiConfig) {
        ensureAccessoryWifi(config.ssid, config.passphrase, config.channel) {}
    }

    fun requestJoinFromStart(start: Iap2Csm.CarPlayStart) {
        if (start.ssid.isNullOrBlank()) return
        ensureAccessoryWifi(start.ssid, start.passphrase, start.channel) {}
    }

    /**
     * When [ssid] is empty or already connected, invokes [onReady] immediately.
     * Otherwise requests a Wi‑Fi specifier and invokes [onReady] after bind.
     * Duplicate ensure for the same SSID coalesces; only the latest waiters run once.
     */
    @SuppressLint("MissingPermission")
    fun ensureAccessoryWifi(
        ssidRaw: String?,
        passphrase: String?,
        channel: Int?,
        onReady: () -> Unit,
    ) {
        val ssid = ssidRaw?.trim().orEmpty()
        if (ssid.isEmpty()) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff skipped: empty SSID — dial on current network")
            onReady()
            return
        }
        val current = currentSsid()
        if (!current.isNullOrBlank() && current.equals(ssid, ignoreCase = true)) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff skipped: already on ssid=$ssid")
            onReady()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(CarPlayConsts.TAG, "WiFi handoff needs API 29+; ssid=$ssid — dial anyway")
            onReady()
            return
        }

        val existing = pendingSsid.get()
        if (existing != null && existing.equals(ssid, ignoreCase = true) && callbackRef.get() != null) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff already pending ssid=$ssid — queue dial")
            pendingReady.add(onReady)
            return
        }

        releaseCallbacksOnly()
        pendingSsid.set(ssid)
        readyFired.set(false)
        pendingReady.clear()
        pendingReady.add(onReady)

        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) {
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(CarPlayConsts.TAG, "WiFi handoff available ssid=$ssid")
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "bindProcessToNetwork failed", e)
                    }
                    fireReady()
                }

                override fun onUnavailable() {
                    Log.w(CarPlayConsts.TAG, "WiFi handoff unavailable ssid=$ssid — dial fallback")
                    fireReady()
                }

                override fun onLost(network: Network) {
                    Log.i(CarPlayConsts.TAG, "WiFi handoff lost ssid=$ssid")
                }
            }
            callbackRef.set(cb)
            cm.requestNetwork(request, cb)
            Log.i(
                CarPlayConsts.TAG,
                "WiFi handoff requested ssid=$ssid ch=$channel — dial waits for available",
            )
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "WiFi handoff request failed", e)
            fireReady()
        }
    }

    private fun fireReady() {
        if (!readyFired.compareAndSet(false, true)) return
        val waiters = pendingReady.toList()
        pendingReady.clear()
        pendingSsid.set(null)
        for (w in waiters) {
            try {
                w()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "WiFi ready callback failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentSsid(): String? = try {
        wifi?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (_: Throwable) {
        null
    }

    private fun releaseCallbacksOnly() {
        val cb = callbackRef.getAndSet(null) ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        releaseCallbacksOnly()
        pendingReady.clear()
        pendingSsid.set(null)
        readyFired.set(false)
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
    }
}
