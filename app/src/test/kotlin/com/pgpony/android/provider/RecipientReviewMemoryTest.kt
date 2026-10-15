// RecipientReviewMemoryTest.kt
// PGPony Android 4.7.0 (item 56, #25): one send through FairEmail asked for key ids up to three
// times, and the recipient review opened each time and forgot the picks. The review's answer is
// kept per calling app and per exact address set for a short time.

package com.pgpony.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientReviewMemoryTest {

    private var clock = 1_000_000L
    private val memory = RecipientReviewMemory(ttlMs = 60_000L, now = { clock })
    private val app = "eu.faircode.email"
    private val picks = longArrayOf(0x1122334455667788L, 0x0102030405060708L)

    @Test
    fun `the same addresses get the same picks back, in any order and case`() {
        memory.remember(app, listOf("Ann <ann@example.org>", "bob@example.org"), picks)
        assertEquals(picks.toList(), memory.recall(app, listOf("BOB@example.org", "ann@EXAMPLE.org"))?.toList())
        assertEquals(picks.toList(), memory.recall(app, listOf("Bob <bob@example.org>", "Ann <ann@example.org>", "ann@example.org"))?.toList())
    }

    @Test
    fun `another address set or another app gets nothing`() {
        memory.remember(app, listOf("ann@example.org"), picks)
        assertNull(memory.recall(app, listOf("ann@example.org", "carol@example.org")))
        assertNull(memory.recall("com.fsck.k9", listOf("ann@example.org")))
        assertNull(memory.recall(app, emptyList()))
    }

    @Test
    fun `an answer expires and clear forgets everything`() {
        memory.remember(app, listOf("ann@example.org"), picks)
        clock += 59_000L
        assertEquals(picks.toList(), memory.recall(app, listOf("ann@example.org"))?.toList())
        clock += 2_000L
        assertNull(memory.recall(app, listOf("ann@example.org")))
        assertNull(memory.lastPicks(app))
        memory.remember(app, listOf("ann@example.org"), picks)
        memory.clear()
        assertNull(memory.recall(app, listOf("ann@example.org")))
    }

    @Test
    fun `last picks follow the newest review of that app`() {
        memory.remember(app, listOf("ann@example.org"), longArrayOf(1L))
        memory.remember(app, listOf("bob@example.org"), longArrayOf(2L, 3L))
        assertEquals(listOf(2L, 3L), memory.lastPicks(app)!!.toList())
    }

    @Test
    fun `an empty answer is not kept`() {
        memory.remember(app, listOf("ann@example.org"), LongArray(0))
        assertNull(memory.recall(app, listOf("ann@example.org")))
    }
}
