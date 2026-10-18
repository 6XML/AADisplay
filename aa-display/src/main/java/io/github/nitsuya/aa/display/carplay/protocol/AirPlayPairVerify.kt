package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay / HomeKit Pair-Verify as Source/controller.
 *
 * Matches DiPlay accessory [PairVerify]: X25519 shared → HKDF Control-Salt keys;
 * DiPlay enables ControlCipher only after M4 (see AirPlaySession ~504–508).
 */
class AirPlayPairVerify(
    private val exchange: (path: String, body: ByteArray, contentType: String) -> ByteArray?,
) {
    data class Result(
        val sharedSecret: ByteArray,
        val writeKey: ByteArray,
        val readKey: ByteArray,
        val controllerId: String,
    )

    fun run(identity: AirPlayPairing.Identity): Result? {
        val xdh = try {
            AirPlayCurveCrypto.x25519Generate()
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "X25519 unavailable", e)
            return null
        }
        val ourPub = xdh.publicKey

        val m1 = Tlv8.encode(
            Tlv8.STATE to Tlv8.u8(1),
            Tlv8.PUBLIC_KEY to ourPub,
        )
        val m2body = exchange("/pair-verify", m1, "application/pairing+tlv8") ?: return null
        val m2 = Tlv8.decode(m2body)
        logTlv("PV-M2", m2)
        Tlv8.errorOf(m2)?.let {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M2 error=$it")
            return null
        }
        if (Tlv8.stateOf(m2) != 2) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify expected M2 state=2 got=${Tlv8.stateOf(m2)}")
            return null
        }
        val accessoryEphPub = m2[Tlv8.PUBLIC_KEY] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M2 missing accessory ephemeral PK")
            return null
        }
        val sealedM2 = m2[Tlv8.ENCRYPTED_DATA] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M2 missing encrypted data")
            return null
        }

        val shared = try {
            AirPlayCurveCrypto.x25519Shared(xdh.privateKey, accessoryEphPub)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "X25519 shared failed", e)
            return null
        }
        val encKey = HkdfSha512.derive(
            shared,
            "Pair-Verify-Encrypt-Salt",
            "Pair-Verify-Encrypt-Info",
            32,
        )
        val subM2 = Tlv8.decode(
            chachaOpen(encKey, nonce("PV-Msg02"), sealedM2) ?: return null,
        )
        val accessoryId = subM2[Tlv8.IDENTIFIER] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M2 sub missing identifier")
            return null
        }
        val accessorySig = subM2[Tlv8.SIGNATURE] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M2 sub missing signature")
            return null
        }
        val expectedAccessoryId = identity.accessoryId.toByteArray(Charsets.UTF_8)
        if (identity.accessoryId.isNotEmpty() &&
            !accessoryId.contentEquals(expectedAccessoryId)
        ) {
            Log.w(
                CarPlayConsts.TAG,
                "AirPlay pair-verify accessory id mismatch " +
                    "got=${accessoryId.toString(Charsets.UTF_8)} " +
                    "want=${identity.accessoryId}",
            )
            return null
        }
        // DiPlay PairVerify.m2: sign(accessoryEph || accessoryId || clientEph).
        // Audi 403 fast path (no prior M6 / no mDNS pk): skip when LTPK unknown — CarDeck same.
        if (identity.accessoryLtpk.size == 32) {
            val accessorySignData = accessoryEphPub + accessoryId + ourPub
            if (!AirPlayCurveCrypto.ed25519Verify(
                    identity.accessoryLtpk,
                    accessorySignData,
                    accessorySig,
                )
            ) {
                Log.w(CarPlayConsts.TAG, "AirPlay pair-verify accessory signature invalid")
                return null
            }
        } else {
            Log.i(CarPlayConsts.TAG, "AirPlay pair-verify skip accessory LTPK check (none saved)")
        }

        val controllerIdBytes = identity.controllerId.toByteArray(Charsets.UTF_8)
        // DiPlay PairVerify.m4: verify(clientEph || controllerId || accessoryEph)
        val controllerSignData = ourPub + controllerIdBytes + accessoryEphPub
        val controllerSig = try {
            AirPlayCurveCrypto.ed25519Sign(identity.edPrivate, controllerSignData)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "AirPlay pair-verify controller sign failed", e)
            return null
        }
        val subM3 = Tlv8.encode(
            Tlv8.IDENTIFIER to controllerIdBytes,
            Tlv8.SIGNATURE to controllerSig,
        )
        val sealedM3 = chachaSeal(encKey, nonce("PV-Msg03"), subM3) ?: return null
        val m3 = Tlv8.encode(
            Tlv8.STATE to Tlv8.u8(3),
            Tlv8.ENCRYPTED_DATA to sealedM3,
        )
        val m4body = exchange("/pair-verify", m3, "application/pairing+tlv8") ?: return null
        val m4 = Tlv8.decode(m4body)
        logTlv("PV-M4", m4)
        Tlv8.errorOf(m4)?.let {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify M4 error=$it")
            return null
        }
        if (Tlv8.stateOf(m4) != 4) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-verify expected M4 state=4 got=${Tlv8.stateOf(m4)}")
            return null
        }

        // DiPlay accessory: readKey=Control-Write, writeKey=Control-Read (PairVerify.kt ~96–98).
        val writeKey = HkdfSha512.derive(shared, "Control-Salt", "Control-Write-Encryption-Key", 32)
        val readKey = HkdfSha512.derive(shared, "Control-Salt", "Control-Read-Encryption-Key", 32)
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay pair-verify ok shared=${shared.size}B writeKey=${writeKey.size}B readKey=${readKey.size}B",
        )
        return Result(
            sharedSecret = shared,
            writeKey = writeKey,
            readKey = readKey,
            controllerId = identity.controllerId,
        )
    }

    private fun logTlv(label: String, map: Map<Int, ByteArray>) {
        val summary = map.entries.joinToString(" ") { (k, v) ->
            "0x${"%02x".format(k)}=${v.size}B"
        }
        Log.i(CarPlayConsts.TAG, "AirPlay pair $label $summary err=${Tlv8.errorOf(map)}")
    }

    companion object {
        fun nonce(tag: String): ByteArray {
            val t = tag.toByteArray(Charsets.UTF_8)
            require(t.size == 8) { "nonce tag must be 8 bytes" }
            return ByteArray(4) + t
        }

        fun chachaSeal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce12),
                )
                cipher.doFinal(plaintext)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "ChaCha20-Poly1305 seal failed", e)
                null
            }
        }

        fun chachaOpen(key: ByteArray, nonce12: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "ChaCha20"),
                    IvParameterSpec(nonce12),
                )
                cipher.doFinal(ciphertextAndTag)
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "ChaCha20-Poly1305 open failed", e)
                null
            }
        }
    }
}
