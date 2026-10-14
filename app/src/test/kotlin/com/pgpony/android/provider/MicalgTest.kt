// MicalgTest.kt
// PGPony Android, 4.7.0 (item 44, #84): RESULT_SIGNATURE_MICALG names the
// hash the detached signature used instead of a fixed pgp-sha256. Signs with
// the passphrase-less /signsub/two-current.pgp fixture from SigningKeyPickTest.

package com.pgpony.android.provider

import com.pgpony.android.crypto.SigningService
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicalgTest {

    private val data = "multipart/signed body\r\n".toByteArray()

    private fun ring() = PGPSecretKeyRing(
        javaClass.getResourceAsStream("/signsub/two-current.pgp")!!.use { it.readBytes() },
        JcaKeyFingerprintCalculator()
    )

    private fun sign(hash: Int, armor: Boolean) =
        SigningService.shared.signDetachedStream(data.inputStream(), ring(), hashAlgorithm = hash, armor = armor)

    @Test
    fun `binary and armored signatures report the hash they were made with`() {
        assertEquals("pgp-sha256", Micalg.of(sign(HashAlgorithmTags.SHA256, armor = false)))
        assertEquals("pgp-sha512", Micalg.of(sign(HashAlgorithmTags.SHA512, armor = false)))
        assertEquals("pgp-sha384", Micalg.of(sign(HashAlgorithmTags.SHA384, armor = true)))
        assertEquals("pgp-sha512", Micalg.of(sign(HashAlgorithmTags.SHA512, armor = true)))
    }

    @Test
    fun `old format and new format headers both parse`() {
        val body = byteArrayOf(4, 0x00, 22, 10, 0, 0)
        val oldFormat = byteArrayOf(0x88.toByte(), body.size.toByte()) + body
        val newFormat = byteArrayOf(0xC2.toByte(), body.size.toByte()) + body
        assertEquals(10, Micalg.signatureHashAlgorithm(oldFormat))
        assertEquals(10, Micalg.signatureHashAlgorithm(newFormat))
        val v6 = byteArrayOf(0xC2.toByte(), 6, 6, 0x00, 27, 14, 0, 0)
        assertEquals("pgp-sha3-512", Micalg.of(v6))
    }

    @Test
    fun `anything that is not a v4 to v6 signature falls back`() {
        assertEquals(Micalg.FALLBACK, Micalg.of(ByteArray(0)))
        assertEquals(Micalg.FALLBACK, Micalg.of("not a signature".toByteArray()))
        val v3 = byteArrayOf(0x88.toByte(), 4, 3, 5, 0, 0)
        assertNull(Micalg.signatureHashAlgorithm(v3))
        val userId = byteArrayOf(0xCD.toByte(), 4, 4, 0, 22, 10)
        assertNull(Micalg.signatureHashAlgorithm(userId))
        assertNull(Micalg.tokenFor(99))
    }
}
