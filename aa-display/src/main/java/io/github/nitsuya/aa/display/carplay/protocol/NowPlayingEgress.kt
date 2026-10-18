package io.github.nitsuya.aa.display.carplay.protocol

/**
 * Outbound path for CarPlay NowPlaying (iAP2 `0x5001`) and artwork file-transfer
 * (session type file-transfer). Implemented by Bluetooth [Iap2Session] (DiPlay) and
 * Wi‑Fi [WifiIap2Link] (MIB3 / Audi).
 */
interface NowPlayingEgress {
    fun sendNowPlaying(mediaItem: Map<Int, ByteArray>, playback: Map<Int, ByteArray>)
    fun sendFileTransfer(datagram: ByteArray)
}
