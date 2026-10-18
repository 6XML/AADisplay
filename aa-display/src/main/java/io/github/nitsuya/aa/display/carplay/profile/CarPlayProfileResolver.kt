package io.github.nitsuya.aa.display.carplay.profile

/**
 * Resolves a [CarPlayHuProfile] from peer signals.
 *
 * Priority:
 * 1. iAP Identification name / model / manufacturer strings
 * 2. Accessory AP SSID (StartSession / Wi‑Fi creds)
 * 3. Bluetooth display name (weak; registry hints only)
 * 4. [CarPlayProfileRegistry.defaultHu]
 *
 * SDP accessory / wireless-CP UUIDs affect dial ranking in BluetoothLink, not
 * which wireless path we take (that needs iAP / SSID / name).
 */
object CarPlayProfileResolver {
    data class Signals(
        val btName: String? = null,
        val iapName: String? = null,
        val iapModel: String? = null,
        val iapManufacturer: String? = null,
        val ssid: String? = null,
    )

    fun resolve(signals: Signals): CarPlayHuProfile {
        matchIap(signals.iapName, signals.iapModel, signals.iapManufacturer)?.let { return it }
        matchSsid(signals.ssid)?.let { return it }
        matchName(signals.btName)?.let { return it }
        return CarPlayProfileRegistry.defaultHu()
    }

    fun matchName(name: String?): CarPlayHuProfile? = matchHints(name) { it.nameHints }

    fun matchSsid(ssid: String?): CarPlayHuProfile? = matchHints(ssid) { it.ssidHints }

    fun matchIap(name: String?, model: String?, manufacturer: String?): CarPlayHuProfile? {
        for (text in listOf(name, model, manufacturer)) {
            matchHints(text) { it.nameHints }?.let { return it }
        }
        return null
    }

    /** True when [name] matches a profile that prefers inbound cafe + HU prepare. */
    fun isInboundPreferredPeer(name: String?): Boolean {
        val profile = matchName(name) ?: return false
        return profile.dialPolicy == CarPlayDialPolicy.INBOUND_PREFERRED
    }

    private fun matchHints(
        text: String?,
        hints: (CarPlayHuProfile) -> List<String>,
    ): CarPlayHuProfile? {
        val n = text?.trim().orEmpty()
        if (n.isEmpty()) return null
        for (profile in CarPlayProfileRegistry.huProfiles) {
            if (profile.id == CarPlayProfileRegistry.generic.id) continue
            val list = hints(profile)
            if (list.any { n.contains(it, ignoreCase = true) }) return profile
        }
        return null
    }
}
