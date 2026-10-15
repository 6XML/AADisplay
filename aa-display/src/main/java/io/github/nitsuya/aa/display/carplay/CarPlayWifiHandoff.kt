package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.nitsuya.aa.display.carplay.protocol.Iap2Csm
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort join of the accessory hotspot after iAP2 `0x5703` / `0x4301`.
 * Callers wait for [ensureAccessoryWifi] before dialing AirPlay TCP.
 *
 * Samsung / ephemeral [WifiNetworkSpecifier] quirks this class must survive:
 * - `WifiInfo.ssid` often stays `<unknown ssid>` after a specifier join, so
 *   "already on SSID" must use our own [boundSsid] or [ConnectivityManager]
 *   transport SSID — never re-request / unregister a live join.
 * - Unregistering the [NetworkCallback] tears the association down immediately.
 * - Framework marks no-internet saved APs `CMD_UNWANTED_NETWORK` (score=0) and
 *   drops them within ~2–3s — that is the DiPlay connect→flash→reconnect loop.
 *   A live specifier callback (local-only, no INTERNET capability) holds the link;
 *   naked "adopt saved network" is only a temporary dial path, then we re-arm hold.
 * - Specifier [onUnavailable] must not leave a zombie [callbackRef] (dial forever
 *   "already pending"), nor abandon the session without a holding re-request.
 */
class CarPlayWifiHandoff(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val pendingReady = CopyOnWriteArrayList<() -> Unit>()
    private val pendingSsid = AtomicReference<String?>(null)
    private val boundSsid = AtomicReference<String?>(null)
    private val boundNetwork = AtomicReference<Network?>(null)
    private val cachedPassphrase = AtomicReference<String?>(null)
    private val cachedChannel = AtomicReference<Int?>(null)
    private val readyFired = AtomicBoolean(false)
    private val wantJoin = AtomicBoolean(false)
    private val rejoinGeneration = AtomicInteger(0)
    private val holding = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun requestJoin(config: Iap2Csm.WifiConfig) {
        ensureAccessoryWifi(config.ssid, config.passphrase, config.channel) {}
    }

    fun requestJoinFromStart(start: Iap2Csm.CarPlayStart) {
        if (start.ssid.isNullOrBlank()) return
        ensureAccessoryWifi(start.ssid, start.passphrase, start.channel) {}
    }

    /**
     * When [ssid] is empty or already bound/connected, invokes [onReady] immediately.
     * Otherwise requests a Wi‑Fi specifier and invokes [onReady] after bind.
     * Duplicate ensure for the same SSID coalesces — never tears a live join.
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
        if (!passphrase.isNullOrEmpty()) {
            cachedPassphrase.set(passphrase)
        }
        if (channel != null) {
            cachedChannel.set(channel)
        }
        wantJoin.set(true)

        if (isBoundTo(ssid) || adoptIfAssociated(ssid, reason = "ensure")) {
            onReady()
            // Saved-network adopt alone is killed by Samsung UNWANTED within seconds.
            ensureHoldingSpecifier(ssid)
            return
        }

        val existing = pendingSsid.get()
        val liveCb = callbackRef.get()
        if (liveCb != null && existing?.equals(ssid, ignoreCase = true) == true) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff already pending ssid=$ssid — queue dial")
            pendingReady.add(onReady)
            if (readyFired.get() || adoptIfAssociated(ssid, reason = "pending-flush")) {
                fireReady()
                ensureHoldingSpecifier(ssid)
            } else {
                scheduleAssociationWatch(ssid)
            }
            return
        }

        if (liveCb != null && boundSsid.get()?.equals(ssid, ignoreCase = true) == true) {
            Log.i(CarPlayConsts.TAG, "WiFi handoff coalesce ssid=$ssid — keep callback")
            pendingReady.add(onReady)
            if (readyFired.get()) {
                fireReady()
            }
            return
        }

        startSpecifierRequest(
            ssid = ssid,
            passphrase = passphrase ?: cachedPassphrase.get(),
            channel = channel,
            onReady = onReady,
            preserveBind = false,
        )
    }

    /**
     * Keep a local-only specifier [NetworkCallback] registered for the whole CP
     * session so Samsung cannot mark the AP UNWANTED and tear TCP mid-stream.
     */
    private fun ensureHoldingSpecifier(ssid: String) {
        if (!wantJoin.get()) return
        if (holding.get() && callbackRef.get() != null &&
            (boundSsid.get()?.equals(ssid, ignoreCase = true) == true ||
                pendingSsid.get()?.equals(ssid, ignoreCase = true) == true)
        ) {
            return
        }
        if (callbackRef.get() != null &&
            pendingSsid.get()?.equals(ssid, ignoreCase = true) == true
        ) {
            holding.set(true)
            return
        }
        Log.i(CarPlayConsts.TAG, "WiFi handoff arm holding specifier ssid=$ssid")
        startSpecifierRequest(
            ssid = ssid,
            passphrase = cachedPassphrase.get(),
            channel = cachedChannel.get(),
            onReady = null,
            preserveBind = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startSpecifierRequest(
        ssid: String,
        passphrase: String?,
        channel: Int?,
        onReady: (() -> Unit)?,
        preserveBind: Boolean,
    ) {
        // Only tear the previous callback when replacing; preserveBind keeps the
        // current L2 association while we try to obtain a holding request.
        if (!preserveBind) {
            releaseCallbacksOnly()
            boundSsid.set(null)
            boundNetwork.set(null)
            readyFired.set(false)
            pendingReady.clear()
        } else if (callbackRef.get() != null) {
            // Already have a live callback — do not unregister (that drops Wi‑Fi).
            if (onReady != null) {
                pendingReady.add(onReady)
                if (readyFired.get()) fireReady()
            }
            return
        }

        pendingSsid.set(ssid)
        if (onReady != null) {
            pendingReady.add(onReady)
        }
        if (!preserveBind) {
            holding.set(false)
        }

        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) {
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            // Local-only: do not require INTERNET/VALIDATED — otherwise Samsung
            // scores the AP 0 and emits CMD_UNWANTED_NETWORK within a few seconds.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
            val generation = rejoinGeneration.incrementAndGet()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(CarPlayConsts.TAG, "WiFi handoff available ssid=$ssid hold=true")
                    holding.set(true)
                    bindNetwork(ssid, network)
                    fireReady()
                }

                override fun onUnavailable() {
                    Log.w(CarPlayConsts.TAG, "WiFi handoff unavailable ssid=$ssid")
                    callbackRef.compareAndSet(this, null)
                    holding.set(false)
                    if (pendingSsid.get()?.equals(ssid, ignoreCase = true) == true) {
                        pendingSsid.compareAndSet(ssid, null)
                    }
                    if (adoptIfAssociated(ssid, reason = "unavailable")) {
                        fireReady()
                        scheduleHoldingRetry(ssid, generation)
                        return
                    }
                    Log.w(CarPlayConsts.TAG, "WiFi handoff unavailable — dial fallback without bind")
                    fireReady()
                    scheduleHoldingRetry(ssid, generation)
                }

                override fun onLost(network: Network) {
                    val current = boundNetwork.get()
                    if (current != null && current !== network) return
                    Log.w(CarPlayConsts.TAG, "WiFi handoff lost ssid=$ssid — schedule rejoin")
                    holding.set(false)
                    boundNetwork.compareAndSet(network, null)
                    boundSsid.compareAndSet(ssid, null)
                    scheduleRejoin(ssid, generation)
                }
            }
            callbackRef.set(cb)
            cm.requestNetwork(request, cb)
            Log.i(
                CarPlayConsts.TAG,
                "WiFi handoff requested ssid=$ssid ch=${channel ?: cachedChannel.get()} " +
                    "preserveBind=$preserveBind — dial waits for available",
            )
            scheduleAssociationWatch(ssid)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "WiFi handoff request failed", e)
            callbackRef.set(null)
            holding.set(false)
            pendingSsid.set(null)
            fireReady()
        }
    }

    private fun scheduleRejoin(ssid: String, generation: Int) {
        if (!wantJoin.get()) return
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (generation != rejoinGeneration.get()) return@postDelayed
            if (adoptIfAssociated(ssid, reason = "rejoin-check")) {
                fireReady()
                ensureHoldingSpecifier(ssid)
                return@postDelayed
            }
            Log.i(CarPlayConsts.TAG, "WiFi handoff rejoin ssid=$ssid after onLost")
            startSpecifierRequest(
                ssid = ssid,
                passphrase = cachedPassphrase.get(),
                channel = cachedChannel.get(),
                onReady = null,
                preserveBind = false,
            )
        }, REJOIN_DELAY_MS)
    }

    private fun scheduleHoldingRetry(ssid: String, generation: Int) {
        if (!wantJoin.get()) return
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (generation != rejoinGeneration.get()) return@postDelayed
            if (holding.get() && callbackRef.get() != null) return@postDelayed
            ensureHoldingSpecifier(ssid)
        }, HOLDING_RETRY_MS)
    }

    private fun scheduleAssociationWatch(ssid: String) {
        val gen = rejoinGeneration.get()
        mainHandler.postDelayed({
            if (!wantJoin.get()) return@postDelayed
            if (gen != rejoinGeneration.get()) return@postDelayed
            if (readyFired.get() && pendingReady.isEmpty() && holding.get()) return@postDelayed
            if (adoptIfAssociated(ssid, reason = "watch")) {
                fireReady()
                ensureHoldingSpecifier(ssid)
            }
        }, ASSOCIATION_WATCH_MS)
    }

    private fun fireReady() {
        if (!readyFired.compareAndSet(false, true)) {
            val extra = pendingReady.toList()
            pendingReady.clear()
            for (w in extra) {
                try {
                    w()
                } catch (e: Throwable) {
                    Log.e(CarPlayConsts.TAG, "WiFi ready callback failed", e)
                }
            }
            return
        }
        val waiters = pendingReady.toList()
        pendingReady.clear()
        for (w in waiters) {
            try {
                w()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "WiFi ready callback failed", e)
            }
        }
    }

    private fun bindNetwork(ssid: String, network: Network) {
        boundSsid.set(ssid)
        boundNetwork.set(network)
        try {
            cm.bindProcessToNetwork(network)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "bindProcessToNetwork failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun adoptIfAssociated(ssid: String, reason: String): Boolean {
        if (isBoundTo(ssid)) {
            boundNetwork.get()?.let { net ->
                try {
                    cm.bindProcessToNetwork(net)
                } catch (_: Throwable) {
                }
            }
            Log.i(CarPlayConsts.TAG, "WiFi handoff skipped: already bound ssid=$ssid ($reason)")
            return true
        }
        val network = findWifiNetwork(ssid) ?: return false
        bindNetwork(ssid, network)
        Log.i(CarPlayConsts.TAG, "WiFi handoff adopted ssid=$ssid via CM ($reason)")
        return true
    }

    private fun isBoundTo(ssid: String): Boolean {
        val bound = boundSsid.get() ?: return false
        return bound.equals(ssid, ignoreCase = true) && boundNetwork.get() != null
    }

    @SuppressLint("MissingPermission")
    private fun findWifiNetwork(ssid: String): Network? {
        val networks = try {
            cm.allNetworks
        } catch (_: Throwable) {
            return null
        }
        for (network in networks) {
            val caps = try {
                cm.getNetworkCapabilities(network)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val infoSsid = wifiSsidFrom(caps) ?: continue
            if (infoSsid.equals(ssid, ignoreCase = true)) return network
        }
        if (isCurrentSsid(ssid)) {
            return cm.activeNetwork?.takeIf { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: networks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        return null
    }

    private fun wifiSsidFrom(caps: NetworkCapabilities): String? {
        val transport = caps.transportInfo
        if (transport is WifiInfo) {
            return normalizeSsid(transport.ssid)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun isCurrentSsid(ssid: String): Boolean {
        val current = currentSsid() ?: return false
        return current.equals(ssid, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun currentSsid(): String? = try {
        normalizeSsid(wifi?.connectionInfo?.ssid)
    } catch (_: Throwable) {
        null
    }

    private fun normalizeSsid(raw: String?): String? {
        val s = raw?.trim()?.trim('"').orEmpty()
        if (s.isEmpty() || s == "<unknown ssid>") return null
        return s
    }

    private fun releaseCallbacksOnly() {
        val cb = callbackRef.getAndSet(null) ?: return
        holding.set(false)
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    /**
     * IPv4 of the accessory AP (DHCP server / default gateway on the joined Wi‑Fi).
     * Used when StartSession only publishes a link-local `fe80::` address.
     */
    @SuppressLint("MissingPermission")
    fun accessoryIpv4Hint(ssid: String?): String? {
        val target = ssid?.trim().orEmpty()
        val network = when {
            target.isNotEmpty() && isBoundTo(target) -> boundNetwork.get()
            target.isNotEmpty() -> findWifiNetwork(target)
            else -> boundNetwork.get()
        } ?: return null
        val lp = try {
            cm.getLinkProperties(network)
        } catch (_: Throwable) {
            null
        } ?: return null
        lp.dhcpServerAddress?.hostAddress?.let { return it }
        for (route in lp.routes) {
            if (!route.isDefaultRoute) continue
            val gw = route.gateway?.hostAddress ?: continue
            if (gw.contains(':')) continue
            return gw
        }
        return null
    }

    fun release() {
        wantJoin.set(false)
        rejoinGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        releaseCallbacksOnly()
        pendingReady.clear()
        pendingSsid.set(null)
        boundSsid.set(null)
        boundNetwork.set(null)
        readyFired.set(false)
        holding.set(false)
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val REJOIN_DELAY_MS = 400L
        private const val ASSOCIATION_WATCH_MS = 1_200L
        private const val HOLDING_RETRY_MS = 1_500L
    }
}
