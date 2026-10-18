package io.github.nitsuya.aa.display.carplay.protocol

import android.content.Context
import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import java.io.File
import java.util.UUID

/**
 * Persistent AirPlay controller (phone) identity + last accessory LTPK.
 *
 * Audi MIB3 rejects anonymous [pair-setup] with HTTP 403 after iAP2 MFi trust;
 * rejoin must [pair-verify] with the same controller Ed25519 key the HU saved.
 * Borrowed from CarDeck's ControllerIdentity file store — we already generate
 * ephemeral keys per setup, which breaks the 403 fast path.
 */
class AirPlayIdentityStore(context: Context) {
    private val dir = File(context.filesDir, "airplay_identity").also { it.mkdirs() }
    private val controllerFile = File(dir, "controller.bin")
    private val accessoryFile = File(dir, "accessory.bin")

    data class Controller(
        val pairingId: String,
        val edPrivate: ByteArray,
        val edPublic: ByteArray,
    )

    data class Accessory(
        val accessoryId: String,
        val accessoryLtpk: ByteArray,
    )

    fun loadOrCreateController(preferredId: String): Controller {
        val existing = loadController()
        if (existing != null) return existing
        val ed = AirPlayCurveCrypto.ed25519Generate()
        val id = preferredId.ifBlank { UUID.randomUUID().toString().uppercase() }
        val created = Controller(id, ed.privateKey, ed.publicKey)
        saveController(created)
        Log.i(CarPlayConsts.TAG, "AirPlay controller identity created id=$id")
        return created
    }

    fun loadController(): Controller? {
        val bytes = runCatching { controllerFile.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 1 + 32 + 32) return null
        val idLen = bytes[0].toInt() and 0xff
        if (bytes.size < 1 + idLen + 32 + 32) return null
        val id = bytes.copyOfRange(1, 1 + idLen).toString(Charsets.UTF_8)
        val priv = bytes.copyOfRange(1 + idLen, 1 + idLen + 32)
        val pub = bytes.copyOfRange(1 + idLen + 32, 1 + idLen + 64)
        return Controller(id, priv, pub)
    }

    fun saveController(controller: Controller) {
        val idBytes = controller.pairingId.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + idBytes.size + 32 + 32)
        out[0] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, out, 1, idBytes.size)
        System.arraycopy(controller.edPrivate, 0, out, 1 + idBytes.size, 32)
        System.arraycopy(controller.edPublic, 0, out, 1 + idBytes.size + 32, 32)
        runCatching { controllerFile.writeBytes(out) }
            .onFailure { Log.w(CarPlayConsts.TAG, "AirPlay controller identity save failed", it) }
    }

    fun loadAccessory(): Accessory? {
        val bytes = runCatching { accessoryFile.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 1 + 32) return null
        val idLen = bytes[0].toInt() and 0xff
        if (bytes.size < 1 + idLen + 32) return null
        val id = bytes.copyOfRange(1, 1 + idLen).toString(Charsets.UTF_8)
        val ltpk = bytes.copyOfRange(1 + idLen, 1 + idLen + 32)
        return Accessory(id, ltpk)
    }

    fun saveAccessory(accessoryId: String, accessoryLtpk: ByteArray) {
        if (accessoryLtpk.size != 32) return
        val idBytes = accessoryId.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + idBytes.size + 32)
        out[0] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, out, 1, idBytes.size)
        System.arraycopy(accessoryLtpk, 0, out, 1 + idBytes.size, 32)
        runCatching { accessoryFile.writeBytes(out) }
            .onFailure { Log.w(CarPlayConsts.TAG, "AirPlay accessory identity save failed", it) }
    }

    fun toPairingIdentity(
        controller: Controller,
        accessory: Accessory?,
    ): AirPlayPairing.Identity = AirPlayPairing.Identity(
        controllerId = controller.pairingId,
        edPrivate = controller.edPrivate,
        edPublic = controller.edPublic,
        accessoryId = accessory?.accessoryId.orEmpty(),
        accessoryLtpk = accessory?.accessoryLtpk ?: ByteArray(0),
    )
}
