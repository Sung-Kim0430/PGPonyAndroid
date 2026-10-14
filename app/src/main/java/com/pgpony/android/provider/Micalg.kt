// Micalg.kt
// PGPony Android, 4.7.0 (item 44, #84)
//
// The micalg token a mail app puts in the multipart/signed Content-Type
// (RFC 3156 section 5) comes from RESULT_SIGNATURE_MICALG. It used to be a
// fixed "pgp-sha256"; it is now read from the hash algorithm octet of the
// detached signature PGPony just made, so it always names the hash the
// signature used. Pure Kotlin apart from Bouncy Castle's armor reader.

package com.pgpony.android.provider

import org.bouncycastle.bcpg.ArmoredInputStream
import java.io.ByteArrayInputStream

object Micalg {

    /** Used when the signature cannot be read, the value PGPony always sent before. */
    const val FALLBACK = "pgp-sha256"

    /**
     * The micalg token for [signature], armored or binary. Falls back to
     * [FALLBACK] when the bytes are not a v4, v5 or v6 signature packet or the
     * hash algorithm has no micalg name.
     */
    fun of(signature: ByteArray): String =
        signatureHashAlgorithm(signature)?.let { tokenFor(it) } ?: FALLBACK

    /** RFC 3156 / RFC 9580 micalg names by OpenPGP hash algorithm ID. */
    fun tokenFor(hashAlgorithm: Int): String? = when (hashAlgorithm) {
        1 -> "pgp-md5"
        2 -> "pgp-sha1"
        3 -> "pgp-ripemd160"
        8 -> "pgp-sha256"
        9 -> "pgp-sha384"
        10 -> "pgp-sha512"
        11 -> "pgp-sha224"
        12 -> "pgp-sha3-256"
        14 -> "pgp-sha3-512"
        else -> null
    }

    /**
     * The hash algorithm octet of the first packet in [signature] when it is
     * a signature packet (tag 2) of version 4, 5 or 6, whose bodies all start
     * version, type, public key algorithm, hash algorithm.
     */
    fun signatureHashAlgorithm(signature: ByteArray): Int? {
        val raw = binary(signature) ?: return null
        val body = firstPacketBody(raw) ?: return null
        if (body.tag != 2 || body.length < 4) return null
        val version = raw[body.offset].toInt() and 0xFF
        if (version !in 4..6) return null
        return raw[body.offset + 3].toInt() and 0xFF
    }

    private fun binary(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if ((bytes[0].toInt() and 0x80) != 0) return bytes
        return try {
            ArmoredInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                .takeIf { it.isNotEmpty() && (it[0].toInt() and 0x80) != 0 }
        } catch (e: Exception) {
            null
        }
    }

    private class Body(val tag: Int, val offset: Int, val length: Int)

    private fun firstPacketBody(b: ByteArray): Body? {
        val first = b[0].toInt() and 0xFF
        if ((first and 0x80) == 0) return null
        return if ((first and 0x40) != 0) {
            val tag = first and 0x3F
            if (b.size < 2) return null
            val l1 = b[1].toInt() and 0xFF
            when {
                l1 < 192 -> Body(tag, 2, minOf(l1, b.size - 2))
                l1 < 224 -> {
                    if (b.size < 3) return null
                    val len = ((l1 - 192) shl 8) + (b[2].toInt() and 0xFF) + 192
                    Body(tag, 3, minOf(len, b.size - 3))
                }
                l1 == 255 -> {
                    if (b.size < 6) return null
                    val len = ((b[2].toInt() and 0xFF) shl 24) or ((b[3].toInt() and 0xFF) shl 16) or
                        ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
                    Body(tag, 6, minOf(len, b.size - 6))
                }
                else -> null
            }
        } else {
            val tag = (first shr 2) and 0x0F
            when (first and 0x03) {
                0 -> if (b.size < 2) null else Body(tag, 2, minOf(b[1].toInt() and 0xFF, b.size - 2))
                1 -> if (b.size < 3) null else Body(
                    tag, 3, minOf(((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF), b.size - 3)
                )
                2 -> if (b.size < 5) null else Body(
                    tag, 5, minOf(
                        ((b[1].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                            ((b[3].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF),
                        b.size - 5
                    )
                )
                else -> Body(tag, 1, b.size - 1)
            }
        }
    }
}
