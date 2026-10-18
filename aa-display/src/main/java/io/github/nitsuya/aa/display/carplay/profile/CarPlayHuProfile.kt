package io.github.nitsuya.aa.display.carplay.profile

/**
 * How we dial a bonded peer on accessory RFCOMM (`…caff`).
 */
enum class CarPlayDialPolicy {
    /**
     * Rank bonded peers by accessory / wireless-CP UUID and dial outbound.
     * Typical DiPlay / generic receiver path.
     */
    OUTBOUND_ACCESSORY,

    /**
     * Prefer the HU dialing our phone UUID (`…cafe`). After bond/ACL, allow one
     * outbound `…caff` attempt. Also arms prepareHeadUnit / leftover-bond repair
     * for peers that resolve to this profile.
     */
    INBOUND_PREFERRED,
}

/**
 * Head-unit behavior + AirPlay session flavor.
 *
 * New car: register another instance in [CarPlayProfileRegistry] with matchers
 * (protocol signals first, [nameHints]/[ssidHints] only as weak fallback).
 * Do not add `contains("audi")` in [io.github.nitsuya.aa.display.carplay.CarPlayBluetoothLink]
 * or [io.github.nitsuya.aa.display.carplay.protocol.Iap2Session].
 *
 * [usesMib3WirelessPath] preserves today's Audi CSM vs DiPlay fork as one proven
 * behavior bundle; future profiles may split these into finer flags.
 */
data class CarPlayHuProfile(
    val id: String,
    val dialPolicy: CarPlayDialPolicy,
    /** Weak BT / iAP name substrings (registry-only; never hardcode at call sites). */
    val nameHints: List<String> = emptyList(),
    /** Weak accessory AP SSID substrings for StartSession → AirPlay dial. */
    val ssidHints: List<String> = emptyList(),
    val userAgent: String,
    val hkp: String,
    val osVersion: String,
    val osBuildVersion: String,
    val sourceVersion: String,
    /**
     * Former `mib3` boolean: MIB3-style auth order, Wi‑Fi announce, AAC media,
     * screen SETUP shape, timing, WifiIap2Link CSM path, etc.
     */
    val usesMib3WirelessPath: Boolean,
)
