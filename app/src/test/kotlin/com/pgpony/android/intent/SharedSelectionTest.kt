// SharedSelectionTest.kt
// PGPony Android 4.7.0 (item 38, #72): several shared files are routed by
// what they are. All encrypted goes to decrypt, a mix asks, and plain files
// keep the package route.

package com.pgpony.android.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedSelectionTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A new-format packet with [tag] and [body]. */
    private fun packet(tag: Int, body: ByteArray): ByteArray {
        require(body.size < 192)
        return bytes(0xC0 or tag, body.size) + body
    }

    private fun body(version: Int, size: Int = 20) = ByteArray(size) { if (it == 0) version.toByte() else 9 }

    private val armoredMessage =
        "-----BEGIN PGP MESSAGE-----\n\nhQEMA1234567890ABCDEF\n=abcd\n-----END PGP MESSAGE-----\n".toByteArray()
    private val armoredSignature =
        "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQ\n=abcd\n-----END PGP SIGNATURE-----\n".toByteArray()
    private val clearSigned =
        ("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nhello\n" +
            "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQ\n-----END PGP SIGNATURE-----\n").toByteArray()
    private val publicKey =
        "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmDMEZ\n-----END PGP PUBLIC KEY BLOCK-----\n".toByteArray()
    private val encryptedEml =
        ("MIME-Version: 1.0\nContent-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\";" +
            " boundary=\"b\"\n\n--b\n").toByteArray()
    private val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100) { 1 }
    private val plain = "just some notes\n".toByteArray()

    private fun kind(b: ByteArray, complete: Boolean = true, bc: (ByteArray) -> Boolean = { false }) =
        SharedSelection.classify(b, complete, bc)

    @Test
    fun armoredAndEnvelopedMessages_areEncrypted() {
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(armoredMessage))
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(encryptedEml))
        // Leading prose before the armor (a pasted mail body) still counts.
        assertEquals(
            SharedSelection.Kind.ENCRYPTED,
            kind("Here it is:\n\n".toByteArray() + armoredMessage)
        )
    }

    @Test
    fun binarySessionKeyPackets_areEncrypted() {
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(packet(1, body(3)) + ByteArray(50)))
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(packet(3, body(4)) + ByteArray(50)))
        // A partial head is enough for an encrypted message.
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(packet(1, body(6)), complete = false))
    }

    @Test
    fun detachedSignatures_areRecognised() {
        assertEquals(SharedSelection.Kind.DETACHED_SIGNATURE, kind(armoredSignature))
        assertEquals(SharedSelection.Kind.DETACHED_SIGNATURE, kind(packet(2, body(4, 40))))
        // A binary signature needs the whole file; a cut-off head is not one.
        assertEquals(SharedSelection.Kind.OTHER, kind(packet(2, body(4, 40)), complete = false))
    }

    @Test
    fun keysClearSignedAndPlainFiles_areOther() {
        assertEquals(SharedSelection.Kind.OTHER, kind(clearSigned))
        assertEquals(SharedSelection.Kind.OTHER, kind(publicKey))
        assertEquals(SharedSelection.Kind.OTHER, kind(plain))
        assertEquals(SharedSelection.Kind.OTHER, kind(ByteArray(0)))
    }

    @Test
    fun bouncyCastleFallback_onlyForUnknownBinary() {
        assertEquals(SharedSelection.Kind.ENCRYPTED, kind(plain, bc = { true }))
        // A known image format never asks BouncyCastle.
        assertEquals(SharedSelection.Kind.OTHER, kind(png, bc = { true }))
    }

    @Test
    fun route_bySelection() {
        val e = SharedSelection.Kind.ENCRYPTED
        val s = SharedSelection.Kind.DETACHED_SIGNATURE
        val o = SharedSelection.Kind.OTHER
        assertEquals(SharedSelection.Route.DECRYPT_ALL, SharedSelection.route(listOf(e, e)))
        assertEquals(SharedSelection.Route.DECRYPT_ALL, SharedSelection.route(List(20) { e }))
        assertEquals(SharedSelection.Route.ASK, SharedSelection.route(listOf(e, o)))
        assertEquals(SharedSelection.Route.ASK, SharedSelection.route(listOf(e, s)))
        assertEquals(SharedSelection.Route.PACKAGE, SharedSelection.route(listOf(s, o)))
        assertEquals(SharedSelection.Route.PACKAGE, SharedSelection.route(listOf(o, o)))
        assertEquals(SharedSelection.Route.PACKAGE, SharedSelection.route(emptyList()))
    }
}
