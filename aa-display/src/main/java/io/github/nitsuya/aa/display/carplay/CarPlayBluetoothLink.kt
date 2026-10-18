package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.nitsuya.aa.display.CoreApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * RFCOMM transport for Source-role iAP2 against a HU / diplay receiver.
 *
 * - Listen on **phone** UUID (`…cafe`, "CarPlay Phone") — DiPlay dials this on reconnect.
 * - Listen on **accessory** UUID (`…caff`, "CarPlay Accessory"). Audi dials `…cafe` and rejects outbound.
 * - Listen on the wireless-CarPlay capability UUID so classic EIR/SDP advertises it
 *   (VAG btstack will not offer CarPlay without that UUID).
 * - Dial bonded peers on `…caff`, and dial again when a head unit ACLs or finishes bonding.
 *   A socket that never reaches iAP link-ready does not block that dial.
 *
 * After reboot, [CarPlayProjectionService] often starts before the adapter is ON. Previously
 * that path logged "deferred" and permanently gave up — DiPlay SDP then returns `scn=0`.
 * We now watch [BluetoothAdapter.ACTION_STATE_CHANGED] and arm listen when STATE_ON arrives.
 */
class CarPlayBluetoothLink(
    context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onBytes: (ByteArray) -> Unit,
    /** True once iAP2 link sync has completed on the current socket. */
    private val sessionLive: () -> Boolean = { false },
    private val onPeer: (name: String?, address: String?) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val running = AtomicBoolean(false)
    private val rfcommArmed = AtomicBoolean(false)
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    @Volatile
    private var clientSocket: BluetoothSocket? = null
    private val writeLock = Any()
    private val watchLock = Any()
    private val dialLock = Any()
    private val listenAlive = AtomicInteger(0)
    private var peerGeneration = 0
    private var savedAdapterName: String? = null
    private var btStateReceiver: BroadcastReceiver? = null
    private var peerReceiver: BroadcastReceiver? = null
    private var pairingReceiver: BroadcastReceiver? = null
    /** One accessory-uuid dial after the head unit links, if it does not dial us. */
    @Volatile private var headUnitDialPending = false
    /** Fresh bond. An ACL blip in this window is profile setup, not a deleted key. */
    @Volatile private var headUnitBondedAtMs = 0L
    private val headUnitDialGeneration = AtomicInteger(0)
    private val discoverableRequested = AtomicBoolean(false)

    private val phoneUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_PHONE_RFCOMM_UUID)
    private val accessoryUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID)

    /**
     * Audi reads the adapter name in EIR before it dials. Restore the previous name when
     * the CarPlay link stops so the phone is not left renamed.
     */
    @SuppressLint("MissingPermission")
    private fun presentIphoneName(bt: BluetoothAdapter) {
        val current = try {
            bt.name
        } catch (_: Throwable) {
            null
        }
        if (current != IPHONE_NAME && savedAdapterName == null) {
            savedAdapterName = current
        }
        val ok = try {
            bt.setName(IPHONE_NAME)
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT adapter setName failed", e)
            false
        }
        Log.i(CarPlayConsts.TAG, "BT adapter name -> $IPHONE_NAME ok=$ok was=$current")
        armDiscoverable(bt)
    }

    /**
     * After the HU deletes the phone, inquiry only sees a discoverable adapter.
     * Connectable-only keeps the old bond reconnecting and the new pair times out.
     */
    @SuppressLint("MissingPermission")
    private fun armDiscoverable(bt: BluetoothAdapter) {
        if (!bt.isEnabled) return
        if (bt.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
        if (!discoverableRequested.compareAndSet(false, true)) return
        val ok = try {
            val method = bt.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            method.invoke(bt, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT setScanMode discoverable failed: ${e.cause?.message ?: e.message}")
            false
        }
        Log.i(CarPlayConsts.TAG, "BT discoverable ok=$ok scanMode=${bt.scanMode}")
        if (ok || bt.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return
        // setScanMode needs a privileged uid. The system dialog is the app-visible path.
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.i(CarPlayConsts.TAG, "BT discoverable request dialog shown")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT discoverable request failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        val prev = savedAdapterName ?: return
        savedAdapterName = null
        val bt = adapter ?: return
        if (!bt.isEnabled || prev == IPHONE_NAME) return
        try {
            bt.setName(prev)
            Log.i(CarPlayConsts.TAG, "BT adapter name restored $prev")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT adapter name restore failed", e)
        }
    }

    /**
     * Public BT MAC the head unit already has on the ACL.
     * Read in system_server; the app process only sees 02:00:00:00:00:00.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun localAddress(): String {
        usableMac(runCatching { CoreApi.localBluetoothMac() }.getOrNull())?.let { return it }
        val fromAdapter = try {
            adapter?.address
        } catch (_: Throwable) {
            null
        }
        usableMac(fromAdapter)?.let { return it }
        val fromSettings = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: Throwable) {
            null
        }
        usableMac(fromSettings)?.let { return it }
        Log.w(CarPlayConsts.TAG, "BT MAC unavailable")
        return ""
    }

    private fun usableMac(raw: String?): String? {
        val mac = raw?.trim()?.uppercase().orEmpty()
        if (mac.length < 11 || mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return null
        return mac
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        rfcommArmed.set(false)
        val bt = adapter
        if (bt == null) {
            Log.w(CarPlayConsts.TAG, "Bluetooth adapter missing; iAP RFCOMM unavailable")
            running.set(false)
            return
        }
        if (!bt.isEnabled) {
            Log.w(CarPlayConsts.TAG, "Bluetooth off; iAP RFCOMM waiting for STATE_ON")
            armBtStateWatch()
            return
        }
        beginRfcomm(bt)
    }

    @SuppressLint("MissingPermission")
    private fun beginRfcomm(bt: BluetoothAdapter) {
        if (!running.get()) return
        if (!rfcommArmed.compareAndSet(false, true)) return
        disarmBtStateWatch()
        presentIphoneName(bt)
        armPairingWatch()
        armPeerWatch()
        val records = listenRecords()
        listenAlive.set(records.size)
        for ((name, uuid) in records) {
            thread(name = "carplay-rfcomm-$name", isDaemon = true) {
                try {
                    listen(bt, name, uuid)
                } finally {
                    if (listenAlive.decrementAndGet() == 0 && running.get()) {
                        rfcommArmed.set(false)
                        Log.w(CarPlayConsts.TAG, "RFCOMM listen ended; waiting for Bluetooth again")
                        armBtStateWatch()
                    }
                }
            }
        }
        thread(name = "carplay-rfcomm-dial", isDaemon = true) {
            // Give listen a moment to register SDP before the first sweep.
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return@thread
            }
            // Keep HFP. Do not start A2DP: Audi times that out and shows
            // "cannot establish Bluetooth" instead of dialing iAP.
            // One-shot: the phone still held a key after Audi deleted the device.
            val cleared = clearLeftoverHeadUnitBond(bt)
            if (!cleared) {
                bondedHeadUnit(bt)?.let { prepareHeadUnit(it.address) }
                restoreHeadUnitAudio(bt)
            }
            if (!sessionLive() && bondedHeadUnit(bt) != null) {
                headUnitDialPending = true
            }
            while (running.get() && rfcommArmed.get()) {
                armDiscoverable(bt)
                // Only dial when there is no RFCOMM peer. A connected socket mid-negotiate
                // (!sessionLive) must not be redialed — createRfcommSocket races the pump
                // and leaves Audi silent after our first SYN.
                if (clientSocket?.isConnected != true) {
                    dialBonded(bt)
                }
                try {
                    Thread.sleep(DIAL_RETRY_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun listenRecords(): List<Pair<String, UUID>> = listOf(
        "cafe" to phoneUuid,
        "caff" to accessoryUuid,
        "wcp" to UUID.fromString(CarPlayConsts.WIRELESS_CARPLAY_CAPABILITY_UUID),
    )

    private fun armBtStateWatch() {
        synchronized(watchLock) {
            if (btStateReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    if (!running.get()) return
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                    if (state != BluetoothAdapter.STATE_ON) return
                    val bt = adapter ?: return
                    if (!bt.isEnabled) return
                    Log.i(CarPlayConsts.TAG, "Bluetooth STATE_ON → arm RFCOMM …cafe listen")
                    beginRfcomm(bt)
                }
            }
            btStateReceiver = rx
            try {
                appContext.registerReceiver(
                    rx,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT state watch register failed", e)
                btStateReceiver = null
                running.set(false)
            }
        }
        // Race: adapter may have turned on between isEnabled check and registerReceiver.
        val bt = adapter
        if (bt != null && bt.isEnabled && running.get() && !rfcommArmed.get()) {
            Log.i(CarPlayConsts.TAG, "Bluetooth already ON after watch arm → RFCOMM now")
            beginRfcomm(bt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun armPeerWatch() {
        synchronized(watchLock) {
            if (peerReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (!running.get()) return
                    val action = intent?.action ?: return
                    val device = deviceExtra(intent) ?: return
                    when (action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val bond = intent.getIntExtra(
                                BluetoothDevice.EXTRA_BOND_STATE,
                                BluetoothDevice.BOND_NONE,
                            )
                            if (isHeadUnit(device) && bond == BluetoothDevice.BOND_BONDING) {
                                prepareHeadUnit(device.address)
                            }
                            if (bond != BluetoothDevice.BOND_BONDED) {
                                if (bond == BluetoothDevice.BOND_NONE && isHeadUnit(device)) {
                                    adapter?.let { armDiscoverable(it) }
                                }
                                return
                            }
                            if (isHeadUnit(device)) {
                                headUnitBondedAtMs = SystemClock.elapsedRealtime()
                                prepareHeadUnit(device.address)
                                scheduleHeadUnitDial(device)
                            }
                            Log.i(
                                CarPlayConsts.TAG,
                                "RFCOMM bond complete ${device.address} name=${device.name}",
                            )
                            return
                        }
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            if (isHeadUnit(device)) {
                                prepareHeadUnit(device.address)
                                scheduleHeadUnitDial(device)
                            }
                            Log.i(
                                CarPlayConsts.TAG,
                                "RFCOMM ACL connected ${device.address} name=${device.name}",
                            )
                            return
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            if (isHeadUnit(device)) scheduleStaleBondDrop(device)
                            return
                        }
                        else -> return
                    }
                    if (!shouldOutboundDial(device)) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "RFCOMM $action ${device.address} name=${device.name} — wait for inbound",
                        )
                        return
                    }
                    val target = device
                    thread(name = "carplay-rfcomm-redial", isDaemon = true) {
                        dialDevice(target)
                    }
                }
            }
            peerReceiver = rx
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                appContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT peer watch register failed", e)
                peerReceiver = null
            }
        }
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun disarmPeerWatch() {
        synchronized(watchLock) {
            val rx = peerReceiver ?: return
            peerReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    private fun disarmBtStateWatch() {
        synchronized(watchLock) {
            val rx = btStateReceiver ?: return
            btStateReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialBonded(bt: BluetoothAdapter) {
        val bonded = try {
            bt.bondedDevices?.toList().orEmpty()
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "bondedDevices unavailable", e)
            emptyList()
        }
        if (bonded.isEmpty()) {
            Log.i(CarPlayConsts.TAG, "RFCOMM dial: no bonded peers; rely on listen (…cafe)")
            return
        }
        val ordered = bonded.filter { shouldOutboundDial(it) }.sortedByDescending { dialScore(it) }
        if (ordered.isEmpty()) return
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM dial: trying ${ordered.size} bonded peer(s) on accessory uuid " +
                CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID +
                " order=${ordered.joinToString { "${it.address}/${it.name}" }}",
        )
        for (device in ordered) {
            if (!running.get()) return
            if (clientSocket?.isConnected == true && sessionLive()) return
            val headUnit = isHeadUnit(device)
            val socket = synchronized(dialLock) { openDialSocket(device) }
            if (socket == null) {
                if (headUnit) headUnitDialPending = false
                continue
            }
            if (headUnit) headUnitDialPending = false
            attachPeer(socket, inbound = false, via = "dial")
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun isHeadUnit(device: BluetoothDevice): Boolean {
        val name = device.name.orEmpty()
        return name.contains("audi", ignoreCase = true) || name.contains("mmi", ignoreCase = true)
    }

    /**
     * DiPlay is dialed on `…caff`. Audi normally dials our `…cafe` listener; one
     * outbound attempt is armed after the bond completes. Dialing during
     * BOND_BONDING drops the ACL and cancels the system pairing dialog.
     */
    @SuppressLint("MissingPermission")
    private fun shouldOutboundDial(device: BluetoothDevice): Boolean {
        if (isHeadUnit(device)) {
            return headUnitDialPending && device.bondState == BluetoothDevice.BOND_BONDED
        }
        return dialScore(device) > 0
    }

    private fun prepareHeadUnit(address: String) {
        runCatching { CoreApi.prepareCarPlayHeadUnit(address) }
            .onFailure { Log.w(CarPlayConsts.TAG, "prepareHeadUnit failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleHeadUnitDial(device: BluetoothDevice) {
        if (!isHeadUnit(device)) return
        headUnitDialPending = true
        val gen = headUnitDialGeneration.incrementAndGet()
        thread(name = "carplay-hu-dial", isDaemon = true) {
            try {
                Thread.sleep(800)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (gen != headUnitDialGeneration.get() || !running.get()) return@thread
            if (!shouldOutboundDial(device)) return@thread
            dialDevice(device)
        }
    }

    /** Keep HFP so the HU still has a phone link. A2DP is not part of CarPlay. */
    @SuppressLint("MissingPermission")
    private fun restoreHeadUnitAudio(bt: BluetoothAdapter) {
        val targets = try {
            bt.bondedDevices?.filter { isHeadUnit(it) }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        if (targets.isEmpty()) return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                for (device in targets) {
                    val ok = connectProfile(proxy, device)
                    Log.i(
                        CarPlayConsts.TAG,
                        "BT profile=$profile connect ${device.address} ok=$ok",
                    )
                }
                bt.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }
        bt.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun bondedHeadUnit(bt: BluetoothAdapter): BluetoothDevice? {
        return try {
            bt.bondedDevices?.firstOrNull { isHeadUnit(it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Audi already deleted this phone, but the adapter still has the old link key.
     * A new pair then times out. Clear that key once; later deletes are handled
     * when the ACL actually drops.
     */
    @SuppressLint("MissingPermission")
    private fun clearLeftoverHeadUnitBond(bt: BluetoothAdapter): Boolean {
        val marker = java.io.File(appContext.filesDir, "aadisplay_bt_repair_v1")
        if (marker.exists()) return false
        val hu = bondedHeadUnit(bt) ?: return false
        Log.i(
            CarPlayConsts.TAG,
            "BT clear leftover HU bond ${hu.address} so a deleted Audi can pair",
        )
        removeBond(hu)
        try {
            marker.writeText(hu.address)
        } catch (_: Throwable) {
        }
        return true
    }

    /**
     * After ACL loss: stay discoverable so the HU can come back.
     *
     * Do **not** [removeBond] here. Force-stop / APK install / Wi‑Fi handoff all drop
     * ACL briefly while the session is not yet live — deleting the HU bond left the
     * phone with no Audi entry and "连不上" until a full re-pair. One-shot leftover
     * repair stays in [clearLeftoverHeadUnitBond] only.
     */
    private fun scheduleStaleBondDrop(device: BluetoothDevice) {
        if (sessionLive()) return
        thread(name = "carplay-bt-stale-bond", isDaemon = true) {
            try {
                Thread.sleep(3_000)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!running.get() || sessionLive()) return@thread
            if (isDeviceConnected(device)) return@thread
            Log.i(
                CarPlayConsts.TAG,
                "BT ACL loss ${device.address} name=${device.name} " +
                    "bond=${device.bondState} — keep bond, arm discoverable",
            )
            adapter?.let { armDiscoverable(it) }
        }
    }

    private fun armPairingWatch() {
        synchronized(watchLock) {
            if (pairingReceiver != null || !running.get()) return
            val rx = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                    val device = deviceExtra(intent) ?: return
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.ERROR,
                    )
                    Log.i(
                        CarPlayConsts.TAG,
                        "BT pairing request ${device.address} name=${device.name} " +
                            "variant=$variant bond=${device.bondState}",
                    )
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        removeBond(device)
                        try {
                            abortBroadcast()
                        } catch (_: Throwable) {
                        }
                        return
                    }
                    val confirmed = try {
                        device.setPairingConfirmation(true)
                    } catch (e: Throwable) {
                        Log.w(CarPlayConsts.TAG, "BT pairing confirm failed", e)
                        false
                    }
                    if (!confirmed) {
                        Log.i(
                            CarPlayConsts.TAG,
                            "BT pairing left to system dialog ${device.address}",
                        )
                        return
                    }
                    try {
                        abortBroadcast()
                    } catch (_: Throwable) {
                    }
                }
            }
            pairingReceiver = rx
            try {
                val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
                appContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "BT pairing watch register failed", e)
                pairingReceiver = null
            }
        }
    }

    private fun disarmPairingWatch() {
        synchronized(watchLock) {
            val rx = pairingReceiver ?: return
            pairingReceiver = null
            try {
                appContext.unregisterReceiver(rx)
            } catch (_: Throwable) {
            }
        }
    }

    private fun removeBond(device: BluetoothDevice) {
        try {
            val ok = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean
            Log.i(CarPlayConsts.TAG, "BT removeBond ${device.address} ok=$ok")
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT removeBond failed", e)
        }
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun connectProfile(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            method.invoke(proxy, device) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "BT profile connect failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialScore(device: BluetoothDevice): Int {
        var score = 0
        val name = device.name.orEmpty()
        if (name.contains("audi", ignoreCase = true) || name.contains("mmi", ignoreCase = true)) {
            score += 2
        }
        val uuids = try {
            device.uuids
        } catch (_: Throwable) {
            null
        }
        if (uuids?.any { it.uuid == accessoryUuid } == true) score += 1
        return score
    }

    @SuppressLint("MissingPermission")
    private fun dialDevice(device: BluetoothDevice) {
        val socket = synchronized(dialLock) { openDialSocket(device) } ?: return
        attachPeer(socket, inbound = false, via = "dial")
    }

    /**
     * Connect a dial socket. Caller holds [dialLock]. Does not pump; the caller attaches.
     * An iAP-live session is left alone. An idle socket is closed so Audi can take the link.
     */
    @SuppressLint("MissingPermission")
    private fun openDialSocket(device: BluetoothDevice): BluetoothSocket? {
        if (!running.get()) return null
        val current = clientSocket
        if (current?.isConnected == true) {
            val currentAddr = try {
                current.remoteDevice?.address
            } catch (_: Throwable) {
                null
            }
            if (currentAddr.equals(device.address, ignoreCase = true)) return null
            if (sessionLive()) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM dial ${device.address} skipped; live session on $currentAddr",
                )
                return null
            }
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM drop idle peer $currentAddr for ${device.address} name=${device.name}",
            )
            synchronized(writeLock) {
                if (clientSocket === current) {
                    current.closeQuietly()
                    clientSocket = null
                }
            }
        }
        var socket: BluetoothSocket? = null
        try {
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM connecting to ${device.address} name=${device.name} " +
                    "uuid=${CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID}",
            )
            socket = device.createRfcommSocketToServiceRecord(accessoryUuid)
            socket.connect()
            if (!running.get()) {
                socket.closeQuietly()
                return null
            }
            if (clientSocket?.isConnected == true && sessionLive()) {
                Log.i(
                    CarPlayConsts.TAG,
                    "RFCOMM dial ${device.address} ignored; live session already active",
                )
                socket.closeQuietly()
                return null
            }
            return socket
        } catch (e: Throwable) {
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM dial ${device.address} failed: ${e.message}",
            )
            socket?.closeQuietly()
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(bt: BluetoothAdapter, recordName: String, uuid: UUID) {
        val sdpName = when (recordName) {
            "caff" -> "CarPlay Accessory"
            "wcp" -> "Wireless CarPlay"
            else -> "CarPlay Phone"
        }
        try {
            val server = bt.listenUsingRfcommWithServiceRecord(sdpName, uuid)
            synchronized(writeLock) { serverSockets += server }
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM listening $recordName sdp=\"$sdpName\" uuid=$uuid",
            )
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                if (!running.get()) {
                    socket.closeQuietly()
                    break
                }
                if (clientSocket?.isConnected == true && sessionLive()) {
                    Log.i(
                        CarPlayConsts.TAG,
                        "RFCOMM accept ignored ($recordName); live session already active",
                    )
                    socket.closeQuietly()
                    continue
                }
                if (clientSocket?.isConnected == true) {
                    // sessionLive is false only for a truly idle socket (no iAP yet).
                    Log.i(CarPlayConsts.TAG, "RFCOMM accept replacing idle session via $recordName")
                }
                attachPeer(socket, inbound = true, via = recordName)
            }
            synchronized(writeLock) { serverSockets.remove(server) }
            try {
                server.close()
            } catch (_: Throwable) {
            }
        } catch (e: Throwable) {
            if (running.get()) {
                Log.e(CarPlayConsts.TAG, "RFCOMM listen/accept failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachPeer(socket: BluetoothSocket, inbound: Boolean, via: String = "dial") {
        val generation = synchronized(writeLock) {
            if (clientSocket != null && clientSocket !== socket) {
                clientSocket?.closeQuietly()
            }
            clientSocket = socket
            ++peerGeneration
        }
        val address = try {
            socket.remoteDevice?.address
        } catch (_: Throwable) {
            null
        }
        val name = try {
            socket.remoteDevice?.name
        } catch (_: Throwable) {
            null
        }
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM peer ${if (inbound) "accepted" else "connected"} via=$via " +
                "$address name=$name",
        )
        onPeer(name, address)
        onConnected()
        pump(socket)
        synchronized(writeLock) {
            if (peerGeneration == generation && clientSocket === socket) {
                clientSocket = null
            }
        }
        // Always notify. A newer peer already incremented the session user count,
        // so this close is ignored there and does not tear down the replacement.
        onDisconnected()
        Log.i(
            CarPlayConsts.TAG,
            if (peerGeneration == generation) "RFCOMM peer session ended" else "RFCOMM peer $address replaced",
        )
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }

    private fun pump(socket: BluetoothSocket) {
        val input = socket.inputStream
        val buf = ByteArray(4096)
        while (running.get()) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            onBytes(buf.copyOf(n))
        }
        Log.i(CarPlayConsts.TAG, "RFCOMM pump ended")
    }

    /** Write raw iAP2 bytes (detect marker or FF5A frame). */
    fun sendRaw(bytes: ByteArray) {
        val out = synchronized(writeLock) { clientSocket?.outputStream } ?: return
        try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "RFCOMM write failed", e)
        }
    }

    fun stop() {
        running.set(false)
        rfcommArmed.set(false)
        restoreAdapterName()
        disarmBtStateWatch()
        disarmPeerWatch()
        disarmPairingWatch()
        synchronized(writeLock) {
            clientSocket?.closeQuietly()
            clientSocket = null
            for (server in serverSockets) {
                try {
                    server.close()
                } catch (_: Throwable) {
                }
            }
            serverSockets.clear()
        }
    }

    private companion object {
        const val DIAL_RETRY_MS = 4_000L
        const val IPHONE_NAME = "iPhone"
    }
}
