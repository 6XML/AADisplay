package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RFCOMM transport for Source-role iAP2 against a HU / diplay receiver.
 *
 * - Listen on **phone** UUID (`…cafe`) — accessory dials this on reconnect (seen with DiPlay).
 * - Dial bonded peers on **accessory** UUID (`…caff`) — first-connect phone→HU path.
 */
class CarPlayBluetoothLink(
    context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onBytes: (ByteArray) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val running = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile
    private var clientSocket: BluetoothSocket? = null
    private val writeLock = Any()

    private val phoneUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_PHONE_RFCOMM_UUID)
    private val accessoryUuid: UUID = UUID.fromString(CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID)

    /** Local BT MAC for DeviceTransportIdentifier / CarPlayAvailability (may be randomized). */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun localAddress(): String {
        val raw = try {
            adapter?.address
        } catch (_: Throwable) {
            null
        }
        return normalizeMac(raw)
    }

    private fun normalizeMac(raw: String?): String {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty() ||
            cleaned == "02:00:00:00:00:00" ||
            cleaned == "00:00:00:00:00:00"
        ) {
            // Android hides the real BT MAC; synthesize a stable locally-administered id.
            return syntheticMac()
        }
        return cleaned.uppercase()
    }

    private fun syntheticMac(): String {
        val seed = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }.orEmpty().ifBlank { "aadisplay" }
        val h = seed.hashCode()
        // Locally administered unicast: set bit1 of first octet, clear multicast bit0.
        val b0 = ((h ushr 24) and 0xFC) or 0x02
        val b1 = (h ushr 16) and 0xFF
        val b2 = (h ushr 8) and 0xFF
        val b3 = h and 0xFF
        val b4 = (seed.length * 17) and 0xFF
        val b5 = 0xAD
        return "%02X:%02X:%02X:%02X:%02X:%02X".format(b0, b1, b2, b3, b4, b5)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            Log.w(CarPlayConsts.TAG, "Bluetooth unavailable; iAP RFCOMM deferred")
            running.set(false)
            return
        }
        thread(name = "carplay-rfcomm-listen", isDaemon = true) {
            listen(bt)
        }
        thread(name = "carplay-rfcomm-dial", isDaemon = true) {
            // Give listen a moment to register SDP before dialing.
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return@thread
            }
            dialBonded(bt)
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
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM dial: trying ${bonded.size} bonded peer(s) on accessory uuid " +
                CarPlayConsts.IAP2_ACCESSORY_RFCOMM_UUID,
        )
        for (device in bonded) {
            if (!running.get()) return
            if (clientSocket?.isConnected == true) return
            tryConnect(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice) {
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
                return
            }
            attachPeer(socket, inbound = false)
        } catch (e: Throwable) {
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM dial ${device.address} failed: ${e.message}",
            )
            socket?.closeQuietly()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(bt: BluetoothAdapter) {
        try {
            val server = bt.listenUsingRfcommWithServiceRecord("Wireless iAP", phoneUuid)
            serverSocket = server
            Log.i(
                CarPlayConsts.TAG,
                "RFCOMM listening (phone/Source) uuid=${CarPlayConsts.IAP2_PHONE_RFCOMM_UUID}",
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
                if (clientSocket?.isConnected == true) {
                    Log.i(CarPlayConsts.TAG, "RFCOMM accept ignored; session already active")
                    socket.closeQuietly()
                    continue
                }
                attachPeer(socket, inbound = true)
            }
        } catch (e: Throwable) {
            if (running.get()) {
                Log.e(CarPlayConsts.TAG, "RFCOMM listen/accept failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachPeer(socket: BluetoothSocket, inbound: Boolean) {
        synchronized(writeLock) {
            clientSocket?.closeQuietly()
            clientSocket = socket
        }
        Log.i(
            CarPlayConsts.TAG,
            "RFCOMM peer ${if (inbound) "accepted" else "connected"} " +
                "${socket.remoteDevice?.address} name=${socket.remoteDevice?.name}",
        )
        onConnected()
        pump(socket)
        synchronized(writeLock) {
            if (clientSocket === socket) {
                clientSocket = null
            }
        }
        onDisconnected()
        Log.i(CarPlayConsts.TAG, "RFCOMM peer session ended")
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
        synchronized(writeLock) {
            clientSocket?.closeQuietly()
            clientSocket = null
        }
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }
}
