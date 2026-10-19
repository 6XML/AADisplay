package io.github.nitsuya.aa.display.carplay

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for the Magisk-rooted aad-usb daemon (`archive/usb-carplay/usb-gadget`).
 *
 * Control: TCP `127.0.0.1:48350`, newline-terminated lines (`ARM` / `DISARM` /
 * `STATE` / `GET CARKIT`). When lockdown publishes carkit, daemon pushes
 * `CARKIT <port>` and this class opens a second socket for the raw iAP2 byte
 * stream (device side).
 */
class CarPlayUsbLink(
    private val ctlHost: String = "127.0.0.1",
    private val ctlPort: Int = DEFAULT_CTL_PORT,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    private val onBytes: ((ByteArray) -> Unit)? = null,
    private val onStage: ((String) -> Unit)? = null,
) {
    companion object {
        const val DEFAULT_CTL_PORT = 48350
    }

    private val wantRun = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    /** Once per arm cycle: ask daemon for usbmux+NCM (0x52 / AirPlay path). */
    private val carplayPromoted = AtomicBoolean(false)
    private val stageRef = AtomicReference("idle")
    private val carkitPort = AtomicInteger(-1)
    /** True while a carkit connect thread is in flight (dedupe push + GET). */
    private val carkitOpening = AtomicBoolean(false)
    private val ctlSock = AtomicReference<Socket?>(null)
    private val carkitSock = AtomicReference<Socket?>(null)
    private val ctlOut = AtomicReference<OutputStreamWriter?>(null)
    private val stageListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun currentStage(): String = stageRef.get()
    fun isArmed(): Boolean = armed.get()
    fun isCarkitLive(): Boolean = carkitSock.get()?.isConnected == true

    fun addStageListener(listener: (String) -> Unit) {
        stageListeners.add(listener)
        listener(stageRef.get())
    }

    fun removeStageListener(listener: (String) -> Unit) {
        stageListeners.remove(listener)
    }

    fun start() {
        if (!wantRun.compareAndSet(false, true)) return
        Thread({
            while (wantRun.get()) {
                if (!maintainControl()) {
                    try {
                        Thread.sleep(1_500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "aad-usb-ctl").apply { isDaemon = true }.start()
    }

    fun stop() {
        wantRun.set(false)
        disarm()
        closeCarkit("stop")
        closeCtl()
    }

    fun arm() {
        armed.set(true)
        carplayPromoted.set(false)
        sendLine("ARM")
    }

    fun disarm() {
        armed.set(false)
        carplayPromoted.set(false)
        sendLine("DISARM")
        closeCarkit("disarm")
    }

    fun sendRaw(bytes: ByteArray): Boolean {
        val s = carkitSock.get() ?: return false
        return try {
            val out = s.getOutputStream()
            out.write(bytes)
            out.flush()
            true
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "USB carkit send failed", e)
            closeCarkit("send-fail")
            false
        }
    }

    private fun maintainControl(): Boolean {
        return try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(ctlHost, ctlPort), 2_000)
            sock.soTimeout = 0
            ctlSock.set(sock)
            val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            ctlOut.set(writer)
            writer.write("VER\n")
            writer.write("STATE\n")
            if (armed.get()) writer.write("ARM\n")
            writer.flush()
            Log.i(CarPlayConsts.TAG, "aad-usb control connected $ctlHost:$ctlPort")
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            while (wantRun.get()) {
                val line = reader.readLine() ?: break
                handleCtlLine(line.trim())
            }
            false
        } catch (e: Throwable) {
            if (wantRun.get()) {
                Log.w(CarPlayConsts.TAG, "aad-usb control lost: ${e.message}")
                publishStage("error")
            }
            false
        } finally {
            closeCtl()
        }
    }

    private fun handleCtlLine(line: String) {
        if (line.isEmpty()) return
        Log.d(CarPlayConsts.TAG, "aad-usb << $line")
        when {
            line.startsWith("VER ") -> { /* informational */ }
            line.startsWith("STATE ") -> {
                val stage = line.removePrefix("STATE ").substringBefore(' ').lowercase()
                publishStage(stage)
                // Real iPhone: usbmux-only first; after host mux/lockdown, promote to
                // usbmux+NCM (0x52). Never promote on present (no handshake yet) or
                // carkit (rebuild tears down the live iAP bridge).
                if (armed.get() &&
                    !carplayPromoted.get() &&
                    (stage == "usbmux" || stage == "lockdown")
                ) {
                    if (carplayPromoted.compareAndSet(false, true)) {
                        Log.i(CarPlayConsts.TAG, "aad-usb promote CARPLAY (stage=$stage)")
                        sendLine("CARPLAY")
                    }
                }
                // Daemon also pushes `CARKIT <port>`; only poll when unknown.
                if (stage == "carkit" && carkitPort.get() < 0 && !carkitOpening.get()) {
                    sendLine("GET CARKIT")
                }
            }
            line.startsWith("CARKIT ") -> {
                val arg = line.removePrefix("CARKIT ").trim()
                if (arg == "none" || arg.isEmpty()) {
                    carkitPort.set(-1)
                    closeCarkit("carkit-none")
                } else {
                    val port = arg.toIntOrNull() ?: return
                    if (port == carkitPort.get() && carkitSock.get()?.isConnected == true) {
                        return
                    }
                    carkitPort.set(port)
                    openCarkit(port)
                }
            }
            line.startsWith("ACK ") -> { /* ok */ }
            line.startsWith("ERR ") -> Log.w(CarPlayConsts.TAG, "aad-usb $line")
        }
    }

    private fun openCarkit(port: Int) {
        if (carkitSock.get()?.isConnected == true) return
        if (!carkitOpening.compareAndSet(false, true)) return
        Thread({
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(ctlHost, port), 3_000)
                val prev = carkitSock.getAndSet(sock)
                try {
                    prev?.close()
                } catch (_: Throwable) {
                }
                Log.i(CarPlayConsts.TAG, "USB carkit connected 127.0.0.1:$port")
                onConnected?.invoke()
                val buf = ByteArray(8192)
                val input = sock.getInputStream()
                while (wantRun.get()) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    onBytes?.invoke(buf.copyOf(n))
                }
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "USB carkit open/read failed: ${e.message}")
            } finally {
                carkitOpening.set(false)
                closeCarkit("eof")
            }
        }, "aad-usb-carkit").apply { isDaemon = true }.start()
    }

    private fun closeCarkit(reason: String) {
        val s = carkitSock.getAndSet(null) ?: return
        Log.i(CarPlayConsts.TAG, "USB carkit closed ($reason)")
        try {
            s.close()
        } catch (_: Throwable) {
        }
        onDisconnected?.invoke()
    }

    private fun closeCtl() {
        ctlOut.set(null)
        try {
            ctlSock.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
    }

    private fun sendLine(cmd: String) {
        try {
            val w = ctlOut.get() ?: return
            synchronized(w) {
                w.write(cmd)
                w.write("\n")
                w.flush()
            }
        } catch (e: Throwable) {
            Log.w(CarPlayConsts.TAG, "aad-usb send $cmd failed: ${e.message}")
        }
    }

    private fun publishStage(stage: String) {
        stageRef.set(stage)
        onStage?.invoke(stage)
        for (l in stageListeners) {
            try {
                l(stage)
            } catch (_: Throwable) {
            }
        }
    }
}
