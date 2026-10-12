// TrustLevelDontUseTest.kt
// PGPony Android 4.7.0 (item 10, #75): the Don't use level. Trust is stored
// by enum name, so existing rows read back unchanged and the new level
// round-trips without a schema change; the prompt helper picks out only the
// keys marked Don't use, each once.

package com.pgpony.android.data

import com.pgpony.android.crypto.KeyAlgorithm
import com.pgpony.android.ui.components.dontUseKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustLevelDontUseTest {

    private fun entity(fp: String, trust: TrustLevel) = PGPKeyEntity(
        id = "id-$fp",
        fingerprint = fp,
        userID = "Test <test@pgpony.app>",
        userName = "Test",
        userEmail = "test@pgpony.app",
        algorithm = KeyAlgorithm.ED25519_CV25519,
        isKeyPair = false,
        createdAt = 0L,
        trustLevel = trust
    )

    @Test
    fun `stored names read back and Don't use round-trips`() {
        val c = TrustLevelConverter()
        for (name in listOf("UNKNOWN", "UNVERIFIED", "VERIFIED", "ULTIMATE")) {
            assertEquals(name, c.fromTrustLevel(c.toTrustLevel(name)))
        }
        assertEquals("DONT_USE", c.fromTrustLevel(TrustLevel.DONT_USE))
        assertEquals(TrustLevel.DONT_USE, c.toTrustLevel("DONT_USE"))
    }

    @Test
    fun `Don't use ranks above every other level, so a merge keeps it`() {
        assertEquals(TrustLevel.DONT_USE, TrustLevel.entries.last())
        assertTrue(TrustLevel.entries.indexOf(TrustLevel.DONT_USE) > TrustLevel.entries.indexOf(TrustLevel.ULTIMATE))
    }

    @Test
    fun `the prompt lists only keys marked Don't use, each once`() {
        val a = entity("AAAA", TrustLevel.DONT_USE)
        val b = entity("BBBB", TrustLevel.VERIFIED)
        val c = entity("CCCC", TrustLevel.DONT_USE)
        assertEquals(listOf(a, c), dontUseKeys(listOf(a, b, null, c, a)))
        assertTrue(dontUseKeys(listOf(b, null)).isEmpty())
    }
}
