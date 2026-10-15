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

    /**
     * Negotiated / supplied frame rate. The DiPlay host ignores the SETUP `fps` field (its
     * decoder runs at its own 30..60 user setting), but the supply side must still produce
     * 60 frames/s so a 60-configured host is never starved; other hosts (CarPlay Simulator)
     * pace off the SETUP value.
     */
    const val DEFAULT_VIDEO_FPS = 60

    /** Degraded supply rate when the link / encoder cannot hold [DEFAULT_VIDEO_FPS]. */
    const val DEGRADED_VIDEO_FPS = 30

    /** AVC bitrate per supply rate: 60 fps needs headroom for 1280x720 motion. */
    const val BITRATE_FPS60 = 16_000_000
    const val BITRATE_FPS30 = 8_000_000

    /**
     * Periodic IDR interval when the encoder lacks [FEATURE_IntraRefresh].
     * Aligns with scrcpy's default (10s): shorter GOPs (2–5s) cause ~200KB WiFi
     * bursts that hitch DiPlay even at a healthy 60fps supply. DiPlay can still
     * request an IDR via event `forceKeyFrame`.
     */
    const val I_FRAME_INTERVAL_SEC = 10

    /** Sustained actual-fps below this share of target triggers the 60→30 downgrade. */
    const val DEGRADE_RATE_FRACTION = 0.5f

    /**
     * AVC encode resolution. The HU decodes whatever the source sends and scales to its
     * display; the buffer-input RGB→NV12 conversion cost scales with pixel count, so encode
     * at 1280x720 instead of the 1920x720 composite (panes stay 960x720, scaled into the
     * 1280x720 canvas). 16-aligned so the encoder buffer size equals the tee canvas
     * (no per-frame rescale in the encoder).
     */
    const val ENCODE_WIDTH = 1280
    const val ENCODE_HEIGHT = 720

    /**
     * Density for Host overlay Views measured in encode pixels.
     * 160 → 1dp=1px on 1280×720 so AA picker dp/sp match the car canvas.
     */
    const val ENCODE_UI_DENSITY_DPI = 160

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
     * Real app audio feeds type-100 media (48 kHz stereo, 0x8000) and speech/nav
     * (16 kHz mono, 0x10). When no captured audio is flowing, silent frames are sent —
     * unless [SEND_TEST_AUDIO] is enabled, in which case a 440 Hz tone stands in
     * (pipeline debugging only: distinguishes protocol OK vs empty capture).
     */
    const val SEND_TEST_AUDIO = false
}
