package io.github.nitsuya.aa.display.carplay.profile

/**
 * Phone-facing identity advertised over iAP2 / AirPlay SETUP.
 *
 * Add or swap instances in [CarPlayProfileRegistry] for a different handset spoof —
 * do not scatter `"iPhone14,2"` (etc.) in iAP / RTSP call sites.
 * Classic BT adapter name is not spoofed.
 */
data class CarPlaySourceIdentity(
    val id: String,
    val iapName: String,
    val iapModel: String,
    val iapManufacturer: String,
    val iapFirmwareVersion: String,
    val iapHardwareVersion: String,
    val iapLanguage: String,
    val iapRegion: String,
    val airplayModel: String,
    val airplayOsName: String,
    /** Display name in AirPlay session SETUP (`name` key). */
    val airplayName: String,
)
