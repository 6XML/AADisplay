package io.github.nitsuya.aa.display.carplay

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Debug-only: dial our own RTSP server and issue SETUP+RECORD so RTP interleaved path is exercised
 * without a head unit.
 */
object CarPlayLocalLoopback {
    fun armAfterServerReady(portProvider: () -> Int, running: () -> Boolean) {
        thread(name = "carplay-rtsp-loopback", isDaemon = true) {
            var waits = 0
            var port = 0
            while (running() && waits < 100) {
                port = portProvider()
                if (port > 0) break
                Thread.sleep(20)
                waits++
            }
            if (port <= 0 || !running()) return@thread
            // Let Bonjour/registration settle; then self-drive the control plane.
            Thread.sleep(400)
            if (!running()) return@thread
            try {
                Socket("127.0.0.1", port).use { socket ->
                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    fun request(req: String) {
                        writer.write(req)
                        writer.flush()
                        // Drain until blank line of response (ignore body for stub 200s).
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                    }
                    request("OPTIONS rtsp://127.0.0.1/carplay RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                    request(
                        "SETUP rtsp://127.0.0.1/carplay/video RTSP/1.0\r\n" +
                            "CSeq: 2\r\n" +
                            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n",
                    )
                    request(
                        "RECORD rtsp://127.0.0.1/carplay RTSP/1.0\r\n" +
                            "CSeq: 3\r\n" +
                            "Session: AADisplay1\r\n\r\n",
                    )
                    Log.i(CarPlayConsts.TAG, "local RTSP loopback armed RECORD on port=$port")
                    // Keep socket open so interleaved RTP can write.
                    while (running() && !socket.isClosed) {
                        Thread.sleep(500)
                    }
                }
            } catch (e: Throwable) {
                Log.w(CarPlayConsts.TAG, "local RTSP loopback failed: ${e.message}")
            }
        }
    }
}
