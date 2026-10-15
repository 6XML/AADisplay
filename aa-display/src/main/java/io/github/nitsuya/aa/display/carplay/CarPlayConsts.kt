package io.github.nitsuya.aa.display.carplay

/**
 * Wireless CarPlay Source constants for the AADisplay egress path.
 * Protocol shapes follow publicly documented wireless CarPlay / AirPlay control
 * conventions; no third-party CarPlay firmware is vendored here.
 * External read-only refs (iAP2 / auth / Bonjour): docs/CARPLAY_REFS.md
 * (45clouds/WirelessCarPlay, MIT — do not copy binaries/PDFs into this tree).
 */
object CarPlayConsts {
    const val TAG = "AADisplay_CarPlay"

    const val ACTION_CONNECT = "io.github.nitsuya.aa.display.carplay.CONNECT"
    const val ACTION_STOP = "io.github.nitsuya.aa.display.carplay.STOP"

    /** Bonjour / NSD type used by wireless CarPlay control. */
    const val BONJOUR_TYPE = "_carplay-ctrl._tcp."

    const val NOTIFICATION_CHANNEL_ID = "carplay_projection"
    const val NOTIFICATION_ID = 0x0C01

    /** Default test / fallback encode size before HU negotiates. */
    const val DEFAULT_VIDEO_WIDTH = 1920
    const val DEFAULT_VIDEO_HEIGHT = 720
    const val DEFAULT_VIDEO_FPS = 30
    const val DEFAULT_VIDEO_BITRATE = 8_000_000

    /**
     * AVC encode resolution. The HU decodes whatever the source sends and scales to its
     * display; the buffer-input RGB→NV12 conversion cost scales with pixel count, so encode
     * at 1280x720 instead of the 1920x720 composite (panes stay 960x720, scaled into the
     * 1280x720 canvas). 16-aligned so the encoder buffer size equals the tee canvas
     * (no per-frame rescale in the encoder).
     */
    const val ENCODE_WIDTH = 1280
    const val ENCODE_HEIGHT = 720

    /** iAP2 detect preamble only (`FF 55 02 00 EE 10`); link packets use `FF 5A`. */
    const val IAP2_DETECT_START = 0xFF55
    const val IAP2_LINK_START = 0xFF5A

    /**
     * iPhone / Source advertises this SDP UUID so the accessory can dial back (reconnect path).
     * @see IAP2_ACCESSORY_RFCOMM_UUID
     */
    const val IAP2_PHONE_RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe"

    /**
     * Accessory / HU (diplay) advertises this UUID; Source dials it on first connect.
     */
    const val IAP2_ACCESSORY_RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacaff"

    /** @deprecated Use [IAP2_PHONE_RFCOMM_UUID] / [IAP2_ACCESSORY_RFCOMM_UUID]. */
    const val IAP2_RFCOMM_UUID = IAP2_PHONE_RFCOMM_UUID

    /** Debug self-RECORD; keep false when testing against a real HU / diplay simulator. */
    const val ENABLE_LOCAL_RTSP_LOOPBACK = false

    /**
     * Play a synthesized 440 Hz LPCM tone on the CarPlay main audio stream (type 100).
     * v1 stands in for real app audio, which needs a system-side capture tap.
     */
    const val SEND_TEST_AUDIO = true
}
