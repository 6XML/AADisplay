package io.github.nitsuya.aa.display.carplay.protocol

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 / X25519 via BouncyCastle — same primitives DiPlay [AirPlayCrypto] uses.
 * Platform Conscrypt on some OEM API-33 builds lacks `KeyPairGenerator("Ed25519")`.
 */
object AirPlayCurveCrypto {
    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    private val random = SecureRandom()

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }
}
