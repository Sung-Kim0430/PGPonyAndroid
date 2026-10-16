// RecipientReviewTest.kt
// PGPony Android 4.7.0 (item 41, #25): the provider's recipient review. Which
// addresses count as missing and which as Don't use (item 10), what the review
// offers and preselects, and how EXTRA_KEY_IDS_SELECTED merges with the
// recipients already resolved (for encrypt and for GET_KEY_IDS): deduped, and
// refusing ids that are not in the keyring, are revoked, are marked Don't use,
// or are expired when not allowed.

package com.pgpony.android.provider

import com.pgpony.android.crypto.KeyAlgorithm
import com.pgpony.android.data.PGPKeyEntity
import com.pgpony.android.data.TrustLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientReviewTest {

    /** A v4 key whose fingerprint ends in [tail] (16 hex chars), so its key id is [tail]. */
    private fun key(
        tail: String,
        email: String,
        name: String = "Name $tail",
        trust: TrustLevel = TrustLevel.UNKNOWN,
        keyPair: Boolean = false,
        revoked: Boolean = false,
        expiresAt: Long? = null,
        isDefault: Boolean = false
    ) = PGPKeyEntity(
        id = "id-$tail",
        fingerprint = "AAAAAAAAAAAAAAAAAAAAAAAA$tail",
        userID = "$name <$email>",
        userName = name,
        userEmail = email,
        algorithm = KeyAlgorithm.ED25519_CV25519,
        isKeyPair = keyPair,
        trustLevel = trust,
        createdAt = 0L,
        expiresAt = expiresAt,
        isDefault = isDefault,
        isRevoked = revoked
    )

    private fun id(tail: String): Long = java.lang.Long.parseUnsignedLong(tail, 16)

    private val alice = key("1111111111111111", "alice@example.org", name = "Alice")
    private val bob = key("2222222222222222", "bob@example.org", name = "Bob")
    private val bobDontUse = key("3333333333333333", "bob@example.org", name = "Bob old", trust = TrustLevel.DONT_USE)
    private val carolRevoked = key("4444444444444444", "carol@example.org", name = "Carol", revoked = true)
    private val daveExpired = key("5555555555555555", "dave@example.org", name = "Dave", expiresAt = 1L)
    private val me = key("6666666666666666", "me@example.org", name = "Me", keyPair = true, trust = TrustLevel.ULTIMATE)
    private val meDefault = key("7777777777777777", "me@example.org", name = "Me too", keyPair = true, isDefault = true)

    private val ring = listOf(alice, bob, bobDontUse, carolRevoked, daveExpired, me, meDefault)

    @Test
    fun `an address with only a Don't use key is Don't use, anything else unresolved is missing`() {
        assertEquals(RecipientReview.Reason.DONT_USE, RecipientReview.reasonForAddress(false, listOf(bobDontUse)))
        assertEquals(RecipientReview.Reason.MISSING, RecipientReview.reasonForAddress(false, emptyList()))
        // A revoked Don't use key does not make the address Don't use.
        val revokedDontUse = key("8888888888888888", "erin@example.org", trust = TrustLevel.DONT_USE, revoked = true)
        assertEquals(RecipientReview.Reason.MISSING, RecipientReview.reasonForAddress(false, listOf(revokedDontUse)))
        // A usable candidate whose ring failed to load is missing, not Don't use.
        assertEquals(RecipientReview.Reason.MISSING, RecipientReview.reasonForAddress(true, listOf(bobDontUse)))
    }

    @Test
    fun `the review opens only for a non-opportunistic send that has not been reviewed`() {
        assertTrue(RecipientReview.needsReview(1, opportunistic = false, hasSelection = false))
        assertFalse(RecipientReview.needsReview(0, opportunistic = false, hasSelection = false))
        assertFalse(RecipientReview.needsReview(2, opportunistic = true, hasSelection = false))
        assertFalse(RecipientReview.needsReview(2, opportunistic = false, hasSelection = true))
    }

    @Test
    fun `the review offers neither Don't use, revoked nor expired keys unless expired are allowed`() {
        val offered = RecipientReview.offeredKeys(ring, allowExpired = false, senderEmail = null)
        assertFalse(offered.contains(bobDontUse))
        assertFalse(offered.contains(carolRevoked))
        assertFalse(offered.contains(daveExpired))
        assertTrue(offered.containsAll(listOf(alice, bob, me, meDefault)))
        assertTrue(RecipientReview.offeredKeys(ring, allowExpired = true, senderEmail = null).contains(daveExpired))
    }

    @Test
    fun `keys on the sender's address come first`() {
        val offered = RecipientReview.offeredKeys(ring, allowExpired = false, senderEmail = "ME@example.org")
        assertEquals(setOf(me, meDefault), offered.take(2).toSet())
    }

    @Test
    fun `the client's signing key is preselected, else the sender's default own key`() {
        val offered = RecipientReview.offeredKeys(ring, allowExpired = false, senderEmail = "me@example.org")
        assertEquals(me.fingerprint, RecipientReview.preselect(offered, id("6666666666666666"), "me@example.org"))
        assertEquals(meDefault.fingerprint, RecipientReview.preselect(offered, 0L, "me@example.org"))
        // A public key on the sender's address is not "the sender's own key".
        assertNull(RecipientReview.preselect(offered, 0L, "alice@example.org"))
        assertNull(RecipientReview.preselect(offered, 0L, null))
        // A signing key id that is not offered falls back to the address.
        assertEquals(meDefault.fingerprint, RecipientReview.preselect(offered, 42L, "me@example.org"))
    }

    @Test
    fun `selected ids join the resolved recipients once each`() {
        val selection = RecipientReview.resolveSelected(
            longArrayOf(id("1111111111111111"), id("2222222222222222"), id("1111111111111111")),
            ring,
            allowExpired = false
        )
        assertEquals(listOf(alice, bob), selection.accepted)
        assertTrue(selection.refused.isEmpty())
        // Bob was already resolved from his address (fingerprint case differs).
        val toAdd = RecipientReview.notYetResolved(listOf(bob.fingerprint.lowercase()), selection.accepted)
        assertEquals(listOf(alice), toAdd)
    }

    @Test
    fun `unknown, revoked, Don't use and disallowed expired ids are refused`() {
        val unknown = id("9999999999999999")
        val selection = RecipientReview.resolveSelected(
            longArrayOf(
                unknown,
                id("3333333333333333"),
                id("4444444444444444"),
                id("5555555555555555"),
                id("1111111111111111")
            ),
            ring,
            allowExpired = false
        )
        assertEquals(listOf(alice), selection.accepted)
        assertEquals(
            listOf(unknown, id("3333333333333333"), id("4444444444444444"), id("5555555555555555")),
            selection.refused
        )
        val allowed = RecipientReview.resolveSelected(longArrayOf(id("5555555555555555")), ring, allowExpired = true)
        assertEquals(listOf(daveExpired), allowed.accepted)
    }

    @Test
    fun `GET_KEY_IDS after a review returns the resolved ids plus the picks, each once`() {
        val selection = RecipientReview.resolveSelected(
            longArrayOf(id("2222222222222222"), id("1111111111111111")),
            ring,
            allowExpired = false
        )
        // Bob already resolved from his address; Alice was picked in the review.
        assertArrayEquals(
            longArrayOf(id("2222222222222222"), id("6666666666666666"), id("1111111111111111")),
            RecipientReview.mergeKeyIds(listOf(id("2222222222222222"), id("6666666666666666")), selection.accepted)
        )
        // A Don't use pick is refused, so it never reaches the answer.
        val refused = RecipientReview.resolveSelected(longArrayOf(id("3333333333333333")), ring, allowExpired = false)
        assertTrue(refused.accepted.isEmpty())
        assertEquals(listOf(id("3333333333333333")), refused.refused)
        assertArrayEquals(
            longArrayOf(id("2222222222222222")),
            RecipientReview.mergeKeyIds(listOf(id("2222222222222222")), refused.accepted)
        )
    }

    @Test
    fun `the review returns the key ids of the ticked rows in list order`() {
        val offered = listOf(alice, bob, me)
        assertArrayEquals(
            longArrayOf(id("1111111111111111"), id("6666666666666666")),
            RecipientReview.selectedKeyIds(offered, setOf(me.fingerprint, alice.fingerprint, "NOT-OFFERED"))
        )
        assertEquals(0, RecipientReview.selectedKeyIds(offered, emptySet()).size)
    }

    @Test
    fun `search matches name, email, User ID and fingerprint hex`() {
        assertTrue(RecipientReview.matchesQuery(alice, ""))
        assertTrue(RecipientReview.matchesQuery(alice, "ALI"))
        assertTrue(RecipientReview.matchesQuery(alice, "example.org"))
        assertTrue(RecipientReview.matchesQuery(alice, "0x1111 1111"))
        assertFalse(RecipientReview.matchesQuery(alice, "bob"))
        // Fewer than four hex digits does not search fingerprints.
        assertFalse(RecipientReview.matchesQuery(alice, "11"))
    }

    @Test
    fun `key ids format as the API's 16-digit hex`() {
        assertEquals("0x1111111111111111", RecipientReview.formatKeyId(id("1111111111111111")))
        assertEquals("0xFFFFFFFFFFFFFFFF", RecipientReview.formatKeyId(-1L))
    }

    // 4.7.0 RC6 (item 67, #25): the keys a request already includes.
    @Test
    fun `included map pairs fingerprints with labels, normalized, first label wins`() {
        val map = RecipientReview.includedMap(
            arrayOf("aaaa bbbb", "AAAABBBB", "", "CCCC", "DDDD"),
            arrayOf("me@example.org", "other@example.org", "x@example.org", "  ", "0x1111111111111111")
        )
        assertEquals(mapOf("AAAABBBB" to "me@example.org", "DDDD" to "0x1111111111111111"), map)
        assertEquals("AAAABBBB", RecipientReview.normalizeFingerprint("aaaa bbbb"))
    }

    @Test
    fun `included map is empty when either array is missing, and stops at the shorter one`() {
        assertTrue(RecipientReview.includedMap(null, arrayOf("a")).isEmpty())
        assertTrue(RecipientReview.includedMap(arrayOf("AAAA"), null).isEmpty())
        assertEquals(mapOf("AAAA" to "a"), RecipientReview.includedMap(arrayOf("AAAA", "BBBB"), arrayOf("a")))
    }
}
