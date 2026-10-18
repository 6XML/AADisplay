package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AirPlay timing server. On this Audi (pcap: HU `10.174.189.1:6020` → phone),
 * Delphi is the NTP client: it sends `0xD2` to the `timingPort` we advertised
 * and expects `0xD3` back. Sending our own `0xD2` at `:6020` gets no answer,
 * the clock never locks, and the HU drops every screen frame.
 *
 * 32-byte packet: 0x80 0xD2 request / 0xD3 response, marker 0x0007,
 * originate time copied from request offset 24, receive/transmit at 16/24.
 */
class CarPlayTiming private constructor(
    private val socket: DatagramSocket,
    val localAddress: Inet4Address,
) {
    val localPort: Int = socket.localPort
    private val running = AtomicBoolean(true)
    private val seen = AtomicBoolean(false)
    private val lock = Object()
    @Volatile private var remote: InetSocketAddress? = null

    init {
        thread(name = "carplay-timing", isDaemon = true) { pump() }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        synchronized(lock) { lock.notifyAll() }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    /** Accessory timing port from the session SETUP response. Logged only. */
    fun notePeer(host: InetAddress, remotePort: Int) {
        if (remotePort <= 0 || remotePort > 65535) return
        remote = InetSocketAddress(host, remotePort)
        Log.i(
            CarPlayConsts.TAG,
            "timing listen ${localAddress.hostAddress}:$localPort hu=$host:$remotePort",
        )
    }

    /**
     * Block until an inbound timing request has been answered.
     * Call after RECORD, before screen SETUP.
     */
    fun awaitPacket(budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (!seen.get() && running.get() && System.currentTimeMillis() < deadline) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            val stop = synchronized(lock) {
                if (!seen.get()) {
                    try {
                        lock.wait(minOf(200L, remain))
                    } catch (_: InterruptedException) {
                        return@synchronized true
                    }
                }
                false
            }
            if (stop) break
        }
        val ok = seen.get()
        val peer = remote
        Log.i(
            CarPlayConsts.TAG,
            "timing ${peer?.address?.hostAddress}:${peer?.port} local=$localPort firstReply=$ok",
        )
        return ok
    }

    private fun pump() {
        while (running.get()) {
            try {
                readOnce()
            } catch (_: Throwable) {
                break
            }
        }
    }

    /** @return true when a timing request was answered. */
    private fun readOnce(): Boolean {
        return try {
            socket.soTimeout = 1_000
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            if (packet.length < 32) return false
            if ((buf[1].toInt() and 0xff) != 0xD2) return false
            val reply = replyPacket(buf, packet.length)
            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            if (seen.compareAndSet(false, true)) {
                Log.i(
                    CarPlayConsts.TAG,
                    "timing D2 from ${packet.address?.hostAddress}:${packet.port} → D3",
                )
                synchronized(lock) { lock.notifyAll() }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val NTP_UNIX_DELTA = 2_208_988_800L

        /**
         * IPv4 on the same /24 as [peer]. Delphi sends NTP to the address we
         * put in `timingPeerInfo`; an IPv6 or wildcard local address is ignored.
         */
        fun ipv4Beside(local: InetAddress?, peer: InetAddress): Inet4Address? {
            if (local is Inet4Address && !local.isAnyLocalAddress && !local.isLoopbackAddress) {
                return local
            }
            val peer4 = peer as? Inet4Address ?: return null
            val pb = peer4.address
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val b = addr.address
                    if (b[0] == pb[0] && b[1] == pb[1] && b[2] == pb[2]) return addr
                }
            }
            return null
        }

        fun bind(local: Inet4Address): CarPlayTiming? {
            // Wildcard, not the Wi-Fi address. Delphi sends to the TCP source
            // IP + timingPort; a socket bound only to one address missed it
            // when the routing address differed. CSM TimingServer does the same.
            val socket = try {
                io.github.nitsuya.aa.display.carplay.CarPlayAccessoryNet.openUdp()
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "timing bind failed", e)
                return null
            }
            return CarPlayTiming(socket, local)
        }

        private fun replyPacket(request: ByteArray, length: Int): ByteArray {
            val now = ntpNow()
            val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x80.toByte())
            buf.put(0xD3.toByte())
            buf.putShort(7)
            buf.putInt(0)
            if (length >= 32) {
                buf.put(request, 24, 8)
            } else {
                buf.putLong(0)
            }
            buf.putLong(now)
            buf.putLong(now)
            return buf.array()
        }

        private fun ntpNow(): Long {
            val wallMs = System.currentTimeMillis()
            val sec = wallMs / 1000L + NTP_UNIX_DELTA
            val frac = ((wallMs % 1000L) * 0x100000000L) / 1000L
            return (sec shl 32) or (frac and 0xffffffffL)
        }
    }
}
