package io.github.nitsuya.aa.display.carplay.protocol

import android.util.Log
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay / HomeKit Pair-Setup as Source/controller.
 *
 * DiPlay ([PairSetup]) ignores transient FLAGS and only persists the controller LTPK at M6;
 * ControlCipher is enabled only after pair-verify (see DiPlay [AirPlaySession] ~504–508).
 * Default path is therefore persistent M1–M6 so pair-verify can look up our LTPK.
 */
class AirPlayPairing(
    private val controllerId: String,
    private val exchange: (path: String, body: ByteArray, contentType: String) -> ByteArray?,
    /**
     * When set, M5/M6 reuse this Ed25519 pair so Audi can re-[pair-verify] after
     * pair-setup returns HTTP 403 (CarDeck Phase 3-C).
     */
    private val persistentController: AirPlayIdentityStore.Controller? = null,
    private val onAccessoryPaired: ((accessoryId: String, accessoryLtpk: ByteArray) -> Unit)? = null,
) {
    data class Identity(
        val controllerId: String,
        /** Raw 32-byte Ed25519 seed (BouncyCastle). */
        val edPrivate: ByteArray,
        val edPublic: ByteArray,
        val accessoryId: String,
        val accessoryLtpk: ByteArray,
    )

    data class Result(
        val transient: Boolean,
        val sessionKey: ByteArray,
        /** Non-null only for transient M4 shortcut (not used against DiPlay). */
        val writeKey: ByteArray?,
        val readKey: ByteArray?,
        /** Present after persistent M6 — required for pair-verify. */
        val identity: Identity?,
    )

    fun runPairSetup(preferTransient: Boolean = false): Result? {
        val attempts = buildList {
            if (preferTransient) {
                add(Attempt(transient = true, method = Tlv8.METHOD_PAIR_SETUP, stripK = true))
                add(Attempt(transient = true, method = Tlv8.METHOD_PAIR_SETUP, stripK = false))
                add(Attempt(transient = true, method = Tlv8.METHOD_PAIR_SETUP_AUTH, stripK = true))
            }
            add(Attempt(transient = false, method = Tlv8.METHOD_PAIR_SETUP, stripK = true))
            add(Attempt(transient = false, method = Tlv8.METHOD_PAIR_SETUP, stripK = false))
            add(Attempt(transient = false, method = Tlv8.METHOD_PAIR_SETUP_AUTH, stripK = true))
        }
        for (attempt in attempts) {
            Log.i(
                CarPlayConsts.TAG,
                "AirPlay pair-setup try transient=${attempt.transient} " +
                    "method=${attempt.method} stripK=${attempt.stripK}",
            )
            val result = tryPairSetup(attempt) ?: continue
            return result
        }
        return null
    }

    private data class Attempt(val transient: Boolean, val method: Int, val stripK: Boolean)

    private fun tryPairSetup(attempt: Attempt): Result? {
        val srp = Srp6aSha512(stripSessionKeyForProof = attempt.stripK)
        val m1Entries = buildList {
            add(Tlv8.METHOD to Tlv8.u8(attempt.method))
            add(Tlv8.STATE to Tlv8.u8(1))
            if (attempt.transient) add(Tlv8.FLAGS to Tlv8.u32le(Tlv8.FLAG_TRANSIENT))
        }
        val m2body = postPairSetup(Tlv8.encode(m1Entries)) ?: return null
        val m2 = Tlv8.decode(m2body)
        logTlv("M2", m2)
        Tlv8.errorOf(m2)?.let {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M2 error=$it")
            return null
        }
        if (Tlv8.stateOf(m2) != 2) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup expected M2 state=2 got=${Tlv8.stateOf(m2)}")
            return null
        }
        val salt = m2[Tlv8.SALT] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M2 missing salt")
            return null
        }
        val serverB = m2[Tlv8.PUBLIC_KEY] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M2 missing B")
            return null
        }
        val clientProof = try {
            srp.processServerChallenge(salt, serverB)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "AirPlay SRP challenge failed", e)
            return null
        }

        val m3 = Tlv8.encode(
            Tlv8.STATE to Tlv8.u8(3),
            Tlv8.PUBLIC_KEY to srp.publicA,
            Tlv8.PROOF to clientProof,
        )
        val m4body = postPairSetup(m3) ?: return null
        val m4 = Tlv8.decode(m4body)
        logTlv("M4", m4)
        Tlv8.errorOf(m4)?.let {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M4 error=$it")
            return null
        }
        if (Tlv8.stateOf(m4) != 4) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup expected M4 state=4 got=${Tlv8.stateOf(m4)}")
            return null
        }
        val serverProof = m4[Tlv8.PROOF]
        if (serverProof != null && !srp.verifyServerProof(serverProof)) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup server proof mismatch")
            return null
        }
        val sessionKey = srp.sessionKey()
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay pair-setup M4 ok transient=${attempt.transient} " +
                "method=${attempt.method} sessionKey=${sessionKey.size}B",
        )

        if (attempt.transient) {
            val write = HkdfSha512.derive(sessionKey, "Control-Salt", "Control-Write-Encryption-Key", 32)
            val read = HkdfSha512.derive(sessionKey, "Control-Salt", "Control-Read-Encryption-Key", 32)
            return Result(
                transient = true,
                sessionKey = sessionKey,
                writeKey = write,
                readKey = read,
                identity = null,
            )
        }
        return completePersistent(sessionKey)
    }

    private fun completePersistent(sessionKey: ByteArray): Result? {
        val encKey = HkdfSha512.derive(
            sessionKey,
            "Pair-Setup-Encrypt-Salt",
            "Pair-Setup-Encrypt-Info",
            32,
        )
        val signSalt = HkdfSha512.derive(
            sessionKey,
            "Pair-Setup-Controller-Sign-Salt",
            "Pair-Setup-Controller-Sign-Info",
            32,
        )
        val edPrivate: ByteArray
        val edPub: ByteArray
        val idForSign: String
        val persisted = persistentController
        if (persisted != null && persisted.edPrivate.size == 32 && persisted.edPublic.size == 32) {
            edPrivate = persisted.edPrivate
            edPub = persisted.edPublic
            idForSign = persisted.pairingId
        } else {
            val ed = try {
                AirPlayCurveCrypto.ed25519Generate()
            } catch (e: Throwable) {
                Log.e(CarPlayConsts.TAG, "Ed25519 generate failed", e)
                return null
            }
            edPrivate = ed.privateKey
            edPub = ed.publicKey
            idForSign = controllerId
        }
        val idBytes = idForSign.toByteArray(Charsets.UTF_8)
        val toSign = signSalt + idBytes + edPub
        val signature = try {
            AirPlayCurveCrypto.ed25519Sign(edPrivate, toSign)
        } catch (e: Throwable) {
            Log.e(CarPlayConsts.TAG, "Ed25519 sign failed", e)
            return null
        }
        val inner = Tlv8.encode(
            listOf(
                Tlv8.IDENTIFIER to idBytes,
                Tlv8.PUBLIC_KEY to edPub,
                Tlv8.SIGNATURE to signature,
            ),
        )
        val encrypted = chachaSeal(encKey, nonce("PS-Msg05"), inner) ?: return null
        val m5 = Tlv8.encode(Tlv8.STATE to Tlv8.u8(5), Tlv8.ENCRYPTED_DATA to encrypted)
        val m6body = postPairSetup(m5) ?: return null
        val m6 = Tlv8.decode(m6body)
        logTlv("M6", m6)
        Tlv8.errorOf(m6)?.let {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M6 error=$it")
            return null
        }
        if (Tlv8.stateOf(m6) != 6) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup expected M6 state=6")
            return null
        }
        val sealedM6 = m6[Tlv8.ENCRYPTED_DATA] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M6 missing encrypted data")
            return null
        }
        val subM6 = Tlv8.decode(chachaOpen(encKey, nonce("PS-Msg06"), sealedM6) ?: return null)
        val accessoryIdBytes = subM6[Tlv8.IDENTIFIER] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M6 missing accessory id")
            return null
        }
        val accessoryLtpk = subM6[Tlv8.PUBLIC_KEY] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M6 missing accessory LTPK")
            return null
        }
        val accessorySig = subM6[Tlv8.SIGNATURE] ?: run {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup M6 missing accessory signature")
            return null
        }
        val accessorySignKey = HkdfSha512.derive(
            sessionKey,
            "Pair-Setup-Accessory-Sign-Salt",
            "Pair-Setup-Accessory-Sign-Info",
            32,
        )
        val accessorySignData = accessorySignKey + accessoryIdBytes + accessoryLtpk
        if (!AirPlayCurveCrypto.ed25519Verify(accessoryLtpk, accessorySignData, accessorySig)) {
            Log.w(CarPlayConsts.TAG, "AirPlay pair-setup accessory M6 signature invalid")
            return null
        }
        val accessoryId = accessoryIdBytes.toString(Charsets.UTF_8)
        Log.i(
            CarPlayConsts.TAG,
            "AirPlay pair-setup persistent M6 ok accessoryId=$accessoryId ltpk=${accessoryLtpk.size}B",
        )
        onAccessoryPaired?.invoke(accessoryId, accessoryLtpk)
        return Result(
            transient = false,
            sessionKey = sessionKey,
            writeKey = null,
            readKey = null,
            identity = Identity(
                controllerId = idForSign,
                edPrivate = edPrivate,
                edPublic = edPub,
                accessoryId = accessoryId,
                accessoryLtpk = accessoryLtpk,
            ),
        )
    }

    private fun postPairSetup(body: ByteArray): ByteArray? =
        exchange("/pair-setup", body, "application/pairing+tlv8")

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
