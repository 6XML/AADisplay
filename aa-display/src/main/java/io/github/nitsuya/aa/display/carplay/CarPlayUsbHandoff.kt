package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wait for the kernel CDC-NCM iface (typically `usb0`) after aad-usb switches to
 * the CarPlay composite, then bind the process to that [Network] before dialing
 * AirPlay TCP on the addresses from iAP2 `0x4301`.
 *
 * Mirror of [CarPlayWifiHandoff.ensureAccessoryWifi] ensure semantics — coalesce
 * duplicate waits; never tear a live bind while dialers are pending.
 */
class CarPlayUsbHandoff(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wantHold = AtomicBoolean(false)
    private val readyFired = AtomicBoolean(false)
    private val boundNetwork = AtomicReference<Network?>(null)
    private val boundIface = AtomicReference<String?>(null)
    private val pendingReady = CopyOnWriteArrayList<() -> Unit>()
    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    var onBound: ((String?) -> Unit)? = null

    /**
     * Invokes [onReady] once an Ethernet/USB network is available (or immediately
     * if already bound). Polls [NetworkInterface] for `usb*` / `rndis*` as a
     * fallback when ConnectivityManager is slow to publish the NCM link.
     */
    fun ensureUsbNetwork(onReady: () -> Unit) {
        wantHold.set(true)
        if (adoptIfPresent(reason = "ensure")) {
            onReady()
            return
        }
        pendingReady.add(onReady)
        armCallback()
        pollInterfaces()
    }

    fun release() {
        wantHold.set(false)
        pendingReady.clear()
        readyFired.set(false)
        boundNetwork.set(null)
        boundIface.set(null)
        try {
            CarPlayAccessoryNet.clear()
        } catch (_: Throwable) {
        }
        val cb = callbackRef.getAndSet(null)
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
    }

    fun rebindForDial() {
        val net = boundNetwork.get()
        if (net != null) {
            try {
                CarPlayAccessoryNet.set(net)
            } catch (_: Throwable) {
            }
        } else {
            adoptIfPresent(reason = "rebind")
        }
    }

    /** Best-effort local IPv4 on the USB NCM iface (for Bonjour subnet hints). */
    fun localIpv4OnUsb(): String? {
        val iface = boundIface.get() ?: findUsbIfaceName() ?: return null
        return try {
            val nif = NetworkInterface.getByName(iface) ?: return null
            Collections.list(nif.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }

    private fun armCallback() {
        if (callbackRef.get() != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!wantHold.get()) return
                bindNetwork(network, ifaceHint = null, reason = "ethernet-cb")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (!wantHold.get()) return
                bindNetwork(network, ifaceHint = lp.interfaceName, reason = "link-props")
            }

            override fun onLost(network: Network) {
                if (boundNetwork.get() == network) {
                    Log.i(CarPlayConsts.TAG, "USB NCM Network lost")
                    boundNetwork.set(null)
                    readyFired.set(false)
                }
            }
        }
        callbackRef.set(cb)
        try {
            cm.registerNetworkCallback(request, cb, mainHandler)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "USB NetworkCallback register failed", e)
            callbackRef.compareAndSet(cb, null)
        }
    }

    private fun pollInterfaces() {
        Thread({
            repeat(40) {
                if (!wantHold.get() || readyFired.get()) return@Thread
                if (adoptIfPresent(reason = "poll")) return@Thread
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "usb-ncm-poll").apply { isDaemon = true }.start()
    }

    private fun adoptIfPresent(reason: String): Boolean {
        val iface = findUsbIfaceName() ?: return false
        // Prefer a Network whose LinkProperties matches the iface.
        for (net in cm.allNetworks) {
            val lp = try {
                cm.getLinkProperties(net)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (lp.interfaceName == iface) {
                return bindNetwork(net, ifaceHint = iface, reason = reason)
            }
        }
        // No Network object yet — still report iface up so dialers can try raw sockets.
        boundIface.set(iface)
        Log.i(CarPlayConsts.TAG, "USB iface $iface up ($reason) — no Network yet")
        fireReady(mac = null)
        return true
    }

    private fun bindNetwork(network: Network, ifaceHint: String?, reason: String): Boolean {
        val lp = try {
            cm.getLinkProperties(network)
        } catch (_: Throwable) {
            null
        }
        val iface = ifaceHint ?: lp?.interfaceName
        if (iface != null && !isUsbish(iface) && findUsbIfaceName() == null) {
            return false
        }
        boundNetwork.set(network)
        if (iface != null) boundIface.set(iface)
        try {
            CarPlayAccessoryNet.set(network)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "USB AccessoryNet.set failed", e)
        }
        Log.i(CarPlayConsts.TAG, "USB NCM bound iface=$iface ($reason)")
        fireReady(mac = null)
        return true
    }

    private fun fireReady(mac: String?) {
        if (!readyFired.compareAndSet(false, true)) {
            // Already fired — still drain any late waiters.
        }
        onBound?.invoke(mac)
        val waiters = ArrayList(pendingReady)
        pendingReady.clear()
        for (cb in waiters) {
            try {
                cb()
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "USB handoff waiter failed", e)
            }
        }
    }

    private fun findUsbIfaceName(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { nif ->
                    nif.isUp && !nif.isLoopback && isUsbish(nif.name)
                }
                ?.name
        } catch (_: Throwable) {
            null
        }
    }

    private fun isUsbish(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("usb") || n.startsWith("rndis") || n.startsWith("ncm")
    }

    companion object {
        /** Pick a dialable address from `0x4301` list preferring global IPv6 / IPv4. */
        fun pickDialHost(addresses: List<String>): String? {
            val nonLl = addresses.firstOrNull {
                it.isNotBlank() && !it.startsWith("fe80:", ignoreCase = true)
            }
            if (nonLl != null) return nonLl
            return addresses.firstOrNull { it.isNotBlank() }
        }

        fun isIpv6(host: String): Boolean =
            try {
                Inet6Address.getByName(host) is Inet6Address && host.contains(':')
            } catch (_: Throwable) {
                host.contains(':')
            }
    }
}
