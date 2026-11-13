package io.github.immaghzbad.aetherst.shared.net

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Lightweight, self-contained WireGuard cryptographic primitives.
 * Implements BLAKE2s, X25519, and ChaCha20-Poly1305 per WireGuard specifications.
 */
object WireGuardCrypto {

    private val secureRandom = SecureRandom()

    fun randomBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        secureRandom.nextBytes(b)
        return b
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64(str: String): ByteArray {
        return try {
            Base64.decode(str.trim())
        } catch (_: Exception) {
            java.util.Base64.getDecoder().decode(str.trim())
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encode(bytes)
        } catch (_: Exception) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    // ==========================================
    // BLAKE2s (RFC 7693)
    // ==========================================
    object Blake2s {
        private val IV = intArrayOf(
            0x6A09E667, -0x4498517b, 0x3C6EF372, -0x5ab00ac6,
            0x510E527F, -0x64fa9774, 0x1F83D9AB, 0x5BE0CD19
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
        )

        fun hash(data: ByteArray, outLen: Int = 32, key: ByteArray? = null): ByteArray {
            val keyLen = key?.size ?: 0
            require(outLen in 1..32)
            require(keyLen <= 32)

            val h = IV.clone()
            h[0] = h[0] xor (0x01010000 or (keyLen shl 8) or outLen)

            val block = ByteArray(64)
            var blockLen = 0
            var t0 = 0
            var t1 = 0

            fun compress(isLast: Boolean) {
                val v = IntArray(16)
                System.arraycopy(h, 0, v, 0, 8)
                System.arraycopy(IV, 0, v, 8, 8)
                v[12] = v[12] xor t0
                v[13] = v[13] xor t1
                if (isLast) v[14] = v[14].inv()

                val m = IntArray(16)
                for (i in 0 until 16) {
                    m[i] = (block[i * 4].toInt() and 0xFF) or
                            ((block[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            ((block[i * 4 + 2].toInt() and 0xFF) shl 16) or
                            ((block[i * 4 + 3].toInt() and 0xFF) shl 24)
                }

                fun g(r: Int, i: Int, a: Int, b: Int, c: Int, d: Int) {
                    v[a] = v[a] + v[b] + m[SIGMA[r][2 * i]]
                    v[d] = Integer.rotateRight(v[d] xor v[a], 16)
                    v[c] = v[c] + v[d]
                    v[b] = Integer.rotateRight(v[b] xor v[c], 12)
                    v[a] = v[a] + v[b] + m[SIGMA[r][2 * i + 1]]
                    v[d] = Integer.rotateRight(v[d] xor v[a], 8)
                    v[c] = v[c] + v[d]
                    v[b] = Integer.rotateRight(v[b] xor v[c], 7)
                }

                for (r in 0 until 10) {
                    g(r, 0, 0, 4, 8, 12)
                    g(r, 1, 1, 5, 9, 13)
                    g(r, 2, 2, 6, 10, 14)
                    g(r, 3, 3, 7, 11, 15)
                    g(r, 4, 0, 5, 10, 15)
                    g(r, 5, 1, 6, 11, 12)
                    g(r, 6, 2, 7, 8, 13)
                    g(r, 7, 3, 4, 9, 14)
                }

                for (i in 0 until 8) {
                    h[i] = h[i] xor v[i] xor v[i + 8]
                }
            }

            if (key != null && keyLen > 0) {
                System.arraycopy(key, 0, block, 0, keyLen)
                blockLen = 64
            }

            var offset = 0
            while (offset < data.size) {
                if (blockLen == 64) {
                    t0 += 64
                    if (t0 == 0) t1++
                    compress(false)
                    block.fill(0)
                    blockLen = 0
                }
                val toCopy = minOf(64 - blockLen, data.size - offset)
                System.arraycopy(data, offset, block, blockLen, toCopy)
                blockLen += toCopy
                offset += toCopy
            }

            t0 += blockLen
            if (t0 < blockLen) t1++
            if (blockLen < 64) {
                for (i in blockLen until 64) block[i] = 0
            }
            compress(true)

            val out = ByteArray(outLen)
            for (i in 0 until outLen) {
                out[i] = (h[i / 4] ushr ((i % 4) * 8)).toByte()
            }
            return out
        }

        fun mac(data: ByteArray, key: ByteArray, outLen: Int = 16): ByteArray {
            return hash(data, outLen = outLen, key = key)
        }
    }

    // ==========================================
    // X25519 (RFC 7748)
    // ==========================================
    object X25519 {
        private val P = java.math.BigInteger("7ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd", 16)
        private val A24 = java.math.BigInteger.valueOf(121665)

        fun generatePrivateKey(): ByteArray {
            val key = randomBytes(32)
            key[0] = (key[0].toInt() and 248).toByte()
            key[31] = (key[31].toInt() and 127).toByte()
            key[31] = (key[31].toInt() or 64).toByte()
            return key
        }

        fun publicFromPrivate(privateKey: ByteArray): ByteArray {
            val basePoint = ByteArray(32).apply { this[0] = 9 }
            return scalarMult(privateKey, basePoint)
        }

        fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
            val k = clampScalar(scalar)
            var x1 = decodeBigInt(point).mod(P)
            var x2 = java.math.BigInteger.ONE
            var z2 = java.math.BigInteger.ZERO
            var x3 = x1
            var z3 = java.math.BigInteger.ONE
            var swap = 0

            for (t in 254 downTo 0) {
                val kt = (k.shiftRight(t).toInt() and 1)
                swap = swap xor kt
                if (swap == 1) {
                    val tx = x2; x2 = x3; x3 = tx
                    val tz = z2; z2 = z3; z3 = tz
                }
                swap = kt

                val a = (x2.add(z2)).mod(P)
                val aa = (a.multiply(a)).mod(P)
                val b = (x2.subtract(z2)).mod(P)
                val bb = (b.multiply(b)).mod(P)
                val e = (aa.subtract(bb)).mod(P)
                val c = (x3.add(z3)).mod(P)
                val d = (x3.subtract(z3)).mod(P)
                val da = (d.multiply(a)).mod(P)
                val cb = (c.multiply(b)).mod(P)

                x3 = ((da.add(cb)).multiply(da.add(cb))).mod(P)
                z3 = (x1.multiply((da.subtract(cb)).multiply(da.subtract(cb)))).mod(P)
                x2 = (aa.multiply(bb)).mod(P)
                z2 = (e.multiply(aa.add(A24.multiply(e)))).mod(P)
            }

            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }

            val result = (x2.multiply(z2.modInverse(P))).mod(P)
            return encodeBigInt(result, 32)
        }

        private fun clampScalar(scalar: ByteArray): java.math.BigInteger {
            val copy = scalar.clone()
            copy[0] = (copy[0].toInt() and 248).toByte()
            copy[31] = (copy[31].toInt() and 127).toByte()
            copy[31] = (copy[31].toInt() or 64).toByte()
            return decodeBigInt(copy)
        }

        private fun decodeBigInt(bytes: ByteArray): java.math.BigInteger {
            val reversed = ByteArray(bytes.size + 1)
            for (i in bytes.indices) {
                reversed[bytes.size - i] = bytes[i]
            }
            return java.math.BigInteger(reversed)
        }

        private fun encodeBigInt(n: java.math.BigInteger, len: Int): ByteArray {
            val raw = n.toByteArray()
            val out = ByteArray(len)
            for (i in 0 until minOf(raw.size, len)) {
                out[i] = raw[raw.size - 1 - i]
            }
            return out
        }
    }

    // ==========================================
    // ChaCha20-Poly1305 AEAD (RFC 8439)
    // ==========================================
    object ChaCha20Poly1305 {
        fun encrypt(key: ByteArray, nonce: Long, plaintext: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
            val nonceBytes = ByteArray(12)
            // WireGuard uses 8-byte LE counter as nonce in 12-byte buffer
            for (i in 0..7) {
                nonceBytes[4 + i] = ((nonce ushr (i * 8)) and 0xFF).toByte()
            }

            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
                val keySpec = SecretKeySpec(key, "ChaCha20")
                val ivSpec = IvParameterSpec(nonceBytes)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
                if (ad.isNotEmpty()) cipher.updateAAD(ad)
                cipher.doFinal(plaintext)
            } catch (_: Exception) {
                // Java 11 / Android standard cipher name fallback
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                val keySpec = SecretKeySpec(key, "ChaCha20")
                val ivSpec = IvParameterSpec(nonceBytes)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
                if (ad.isNotEmpty()) cipher.updateAAD(ad)
                cipher.doFinal(plaintext)
            }
        }

        fun decrypt(key: ByteArray, nonce: Long, ciphertextAndTag: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
            val nonceBytes = ByteArray(12)
            for (i in 0..7) {
                nonceBytes[4 + i] = ((nonce ushr (i * 8)) and 0xFF).toByte()
            }

            return try {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
                val keySpec = SecretKeySpec(key, "ChaCha20")
                val ivSpec = IvParameterSpec(nonceBytes)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                if (ad.isNotEmpty()) cipher.updateAAD(ad)
                cipher.doFinal(ciphertextAndTag)
            } catch (_: Exception) {
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                val keySpec = SecretKeySpec(key, "ChaCha20")
                val ivSpec = IvParameterSpec(nonceBytes)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                if (ad.isNotEmpty()) cipher.updateAAD(ad)
                cipher.doFinal(ciphertextAndTag)
            }
        }
    }
}

