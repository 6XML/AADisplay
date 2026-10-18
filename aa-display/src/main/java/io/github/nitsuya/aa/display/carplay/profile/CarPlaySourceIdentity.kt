package io.github.nitsuya.aa.display.carplay.profile

/**
 * Phone-facing identity advertised over Bluetooth / iAP2 / AirPlay SETUP.
 *
 * Add or swap instances in [CarPlayProfileRegistry] for a different handset spoof —
 * do not scatter `"iPhone14,2"` (etc.) in BT / iAP / RTSP call sites.
 */
data class CarPlaySourceIdentity(
    val id: String,
    /** Classic BT adapter EIR name while CarPlay RFCOMM is armed. */
    val btAdvertiseName: String,
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
