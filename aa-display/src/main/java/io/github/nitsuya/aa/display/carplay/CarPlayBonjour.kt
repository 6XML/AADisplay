package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers wireless CarPlay control services and optionally registers a local control listener.
 */
class CarPlayBonjour(
    context: Context,
    private val onResolved: (NsdServiceInfo) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val discovering = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)
    private var localPort: Int = 0

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour discovery start failed code=$errorCode")
            discovering.set(false)
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "Bonjour discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            Log.i(CarPlayConsts.TAG, "Bonjour discovery start type=$serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            Log.i(CarPlayConsts.TAG, "Bonjour discovery stopped")
            discovering.set(false)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            Log.i(CarPlayConsts.TAG, "Bonjour found ${info.serviceName} type=${info.serviceType}")
            // Skip our own advertisement — resolving it races with registration and is useless.
            if (info.serviceName == "AADisplay-CarPlay") return
            // NsdManager forbids reusing the same ResolveListener while a resolve is in flight.
            // Two rapid onServiceFound callbacks used to crash the process (listener already in use),
            // which tore down RFCOMM …cafe listen and made DiPlay SDP return scn=0.
            resolveFound(info)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            Log.i(CarPlayConsts.TAG, "Bonjour lost ${serviceInfo?.serviceName}")
        }
    }

    private fun resolveFound(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(CarPlayConsts.TAG, "Bonjour resolve failed code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val resolved = serviceInfo ?: return
                Log.i(
                    CarPlayConsts.TAG,
                    "Bonjour resolved ${resolved.serviceName} host=${resolved.host} port=${resolved.port}",
                )
                onResolved(resolved)
            }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(CarPlayConsts.TAG, "Bonjour resolve skipped: ${e.message}")
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "Bonjour resolveService failed", e)
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "bonjour-registration failed code=$errorCode")
            registered.set(false)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            Log.e(CarPlayConsts.TAG, "bonjour unregistration failed code=$errorCode")
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
            Log.i(CarPlayConsts.TAG, "bonjour-registration ok ${serviceInfo?.serviceName}")
            registered.set(true)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
            registered.set(false)
        }
    }

    fun startDiscovery() {
        if (!discovering.compareAndSet(false, true)) return
        try {
            nsd.discoverServices(CarPlayConsts.BONJOUR_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Throwable) {
            discovering.set(false)
            Log.e(CarPlayConsts.TAG, "Bonjour discoverServices failed", e)
        }
    }

    fun registerControl(port: Int) {
        localPort = port
        val info = NsdServiceInfo().apply {
            serviceName = "AADisplay-CarPlay"
            serviceType = CarPlayConsts.BONJOUR_TYPE
            setPort(port)
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "Bonjour registerService failed", e)
        }
    }

    fun stop() {
        try {
            if (discovering.get()) nsd.stopServiceDiscovery(discoveryListener)
        } catch (_: Throwable) {
        }
        discovering.set(false)
        try {
            if (registered.get()) nsd.unregisterService(registrationListener)
        } catch (_: Throwable) {
        }
        registered.set(false)
    }
}
