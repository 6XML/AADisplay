package io.github.nitsuya.aa.display.carplay.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HomeKit / AirPlay SRP-6a client: RFC 5054 3072-bit group, SHA-512, identity `Pair-Setup`.
 */
class Srp6aSha512(
    private val username: String = "Pair-Setup",
    private val password: String = "3939",
    /** When true, M1 uses K with leading zeros stripped (HAP-python). */
    private val stripSessionKeyForProof: Boolean = true,
) {
    private val random = SecureRandom()
    private val a: BigInteger
    val publicA: ByteArray

    private var salt: ByteArray = ByteArray(0)
    private var publicB: ByteArray = ByteArray(0)
    private var sessionKey: ByteArray = ByteArray(0)
    private var clientProof: ByteArray = ByteArray(0)

    init {
        var secret: BigInteger
        do {
            secret = BigInteger(256, random).mod(N)
        } while (secret == BigInteger.ZERO)
        a = secret
        publicA = padFixed(pow(G, a).toByteArrayUnsigned(), PAD)
    }

    fun processServerChallenge(salt: ByteArray, serverPublicB: ByteArray): ByteArray {
        require(salt.isNotEmpty()) { "empty salt" }
        require(serverPublicB.isNotEmpty()) { "empty B" }
        this.salt = salt.copyOf()
        // Wire public keys are fixed-width; do not strip leading 0x00.
        this.publicB = padFixed(serverPublicB, PAD)

        val A = BigInteger(1, publicA)
        val B = BigInteger(1, this.publicB)
        require(B.mod(N) != BigInteger.ZERO) { "invalid B" }

        val u = Hpad(publicA, this.publicB)
        val x = Hx(this.salt, username, password)
        // k = H(PAD(N) | PAD(g))
        val k = Hpad(padFixed(N.toByteArrayUnsigned(), PAD), padFixed(G.toByteArrayUnsigned(), PAD))

        // S = (B - k * g^x) ^ (a + u*x) mod N
        val gx = pow(G, x)
        val kgx = k.multiply(gx).mod(N)
        val base = B.subtract(kgx).mod(N)
        val exp = a.add(u.multiply(x))
        val S = pow(base, exp)
        val Sb = S.toByteArrayUnsigned()
        // Full 64-byte K for HKDF / session.
        sessionKey = sha512(Sb)
        val kForM = if (stripSessionKeyForProof) stripLeadingZeros(sessionKey) else sessionKey

        // H(N)/H(g) hash *unpadded* group parameters (HAP).
        val hN = sha512(N.toByteArrayUnsigned())
        val hG = sha512(G.toByteArrayUnsigned())
        val hGroup = ByteArray(hN.size) { i -> (hN[i].toInt() xor hG[i].toInt()).toByte() }
        val hU = sha512(username.toByteArray(Charsets.UTF_8))
        clientProof = sha512(hGroup + hU + this.salt + publicA + this.publicB + kForM)
        return clientProof
    }

    fun verifyServerProof(serverProof: ByteArray): Boolean {
        val kForM = if (stripSessionKeyForProof) stripLeadingZeros(sessionKey) else sessionKey
        val expected = sha512(publicA + clientProof + kForM)
        return expected.contentEquals(serverProof)
    }

    fun sessionKey(): ByteArray = sessionKey.copyOf()

    companion object {
        private val N: BigInteger = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G: BigInteger = BigInteger.valueOf(5)
        private const val PAD = 384

        private fun pow(base: BigInteger, exp: BigInteger): BigInteger = base.modPow(exp, N)

        private fun sha512(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(data)

        private fun Hx(salt: ByteArray, user: String, pass: String): BigInteger {
            val inner = sha512("$user:$pass".toByteArray(Charsets.UTF_8))
            return BigInteger(1, sha512(salt + inner))
        }

        private fun Hpad(vararg parts: ByteArray): BigInteger {
            val md = MessageDigest.getInstance("SHA-512")
            for (p in parts) md.update(p)
            return BigInteger(1, md.digest())
        }

        private fun padFixed(src: ByteArray, len: Int): ByteArray {
            if (src.size == len) return src
            if (src.size > len) return src.copyOfRange(src.size - len, src.size)
            val out = ByteArray(len)
            System.arraycopy(src, 0, out, len - src.size, src.size)
            return out
        }

        private fun BigInteger.toByteArrayUnsigned(): ByteArray {
            val raw = toByteArray()
            return if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
                raw.copyOfRange(1, raw.size)
            } else {
                raw
            }
        }

        private fun stripLeadingZeros(bytes: ByteArray): ByteArray {
            var i = 0
            while (i < bytes.size - 1 && bytes[i] == 0.toByte()) i++
            return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
        }
    }
}
