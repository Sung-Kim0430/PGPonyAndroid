// CertificationOrderTest.kt
// PGPony Android, 4.7.0 (item 45)

package com.pgpony.android.ui.keyring

import org.junit.Assert.assertEquals
import org.junit.Test

/** 4.7.0 (item 45, #85): which certifications a User ID shows before Show all. */
class CertificationOrderTest {

    private data class Row(val id: String, val mine: Boolean, val good: Boolean)

    private fun order(vararg rows: Row) =
        orderCertifications(rows.toList(), { it.mine }, { it.good }).map { it.id }

    @Test
    fun ownCertificationsComeFirstThenGoodOnesThenTheRest() {
        assertEquals(
            listOf("mine", "good1", "good2", "bad1", "bad2"),
            order(
                Row("bad1", mine = false, good = false),
                Row("good1", mine = false, good = true),
                Row("mine", mine = true, good = true),
                Row("bad2", mine = false, good = false),
                Row("good2", mine = false, good = true)
            )
        )
    }

    @Test
    fun aRevokedOwnCertificationStillComesBeforeOtherPeoplesGoodOnes() {
        assertEquals(
            listOf("mineGood", "mineRevoked", "good"),
            order(
                Row("good", mine = false, good = true),
                Row("mineRevoked", mine = true, good = false),
                Row("mineGood", mine = true, good = true)
            )
        )
    }

    @Test
    fun equalRowsKeepTheirStoredOrder() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            order(
                Row("a", mine = false, good = true),
                Row("b", mine = false, good = true),
                Row("c", mine = false, good = true),
                Row("d", mine = false, good = true)
            )
        )
    }

    @Test
    fun threeAreShownBeforeShowAll() {
        assertEquals(3, CERTIFICATIONS_SHOWN)
    }
}
