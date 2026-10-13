// FileBatchNamingTest.kt
// PGPony Android 4.7.0 (items 15 and 38, #72): output names for several
// files at once, and names that never overwrite a file already there.

package com.pgpony.android.ui.encrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBatchNamingTest {

    @Test
    fun encryptAndSignNames_addAnExtension() {
        assertEquals("report.pdf.gpg", FileBatchNaming.encryptedName("report.pdf"))
        assertEquals("report.pdf.sig", FileBatchNaming.signatureName("report.pdf"))
        assertEquals("evil.txt.gpg", FileBatchNaming.encryptedName("../x/evil.txt"))
        assertEquals("file.gpg", FileBatchNaming.encryptedName(null))
    }

    @Test
    fun stripPgpExtension_anyCase() {
        assertEquals("report.pdf", FileBatchNaming.stripPgpExtension("report.pdf.asc"))
        assertEquals("REPORT", FileBatchNaming.stripPgpExtension("REPORT.GPG"))
        assertEquals("notes", FileBatchNaming.stripPgpExtension("notes.Pgp"))
        assertNull(FileBatchNaming.stripPgpExtension("photo.jpg"))
        assertNull(FileBatchNaming.stripPgpExtension(".asc"))
    }

    @Test
    fun decryptedName_prefersTheLiteralFilename() {
        assertEquals("original.txt", FileBatchNaming.decryptedName("a.pgp", "original.txt"))
        assertEquals("passwd", FileBatchNaming.decryptedName("a.pgp", "../../etc/passwd"))
        // gpg's for-your-eyes-only marker is not a name.
        assertEquals("msg", FileBatchNaming.decryptedName("msg.asc", "_CONSOLE"))
        assertEquals("msg", FileBatchNaming.decryptedName("msg.asc", "  "))
    }

    @Test
    fun decryptedName_fallsBackToTheSharedName() {
        assertEquals("report.pdf", FileBatchNaming.decryptedName("report.pdf.asc", null))
        assertEquals("REPORT", FileBatchNaming.decryptedName("REPORT.GPG", null))
        assertEquals("decrypted_data.bin", FileBatchNaming.decryptedName("data.bin", null))
        assertEquals("decrypted_.asc", FileBatchNaming.decryptedName(".asc", null))
        assertEquals("decrypted_output", FileBatchNaming.decryptedName(null, null))
    }

    @Test
    fun uniqueName_numbersBeforeTheExtension() {
        assertEquals("a.txt", FileBatchNaming.uniqueName("a.txt", emptySet()))
        assertEquals("a (1).txt", FileBatchNaming.uniqueName("a.txt", setOf("A.TXT")))
        assertEquals("a (2).txt", FileBatchNaming.uniqueName("a.txt", setOf("a.txt", "a (1).txt")))
        assertEquals("notes (1)", FileBatchNaming.uniqueName("notes", setOf("notes")))
        assertEquals(".profile (1)", FileBatchNaming.uniqueName(".profile", setOf(".profile")))
        assertEquals("a.tar (1).gz", FileBatchNaming.uniqueName("a.tar.gz", setOf("a.tar.gz")))
    }

    @Test
    fun uniqueName_keepsThePgpSuffixWithTheOriginalExtension() {
        // Decrypting "photo (1).jpg.gpg" later gives back "photo (1).jpg".
        assertEquals("photo (1).jpg.gpg", FileBatchNaming.uniqueName("photo.jpg.gpg", setOf("photo.jpg.gpg")))
        assertEquals("doc (1).pdf.sig", FileBatchNaming.uniqueName("doc.pdf.sig", setOf("doc.pdf.sig")))
        assertEquals("x (1).sig", FileBatchNaming.uniqueName("x.sig", setOf("x.sig")))
    }

    @Test
    fun uniqueWithin_aBatch() {
        assertEquals(
            listOf("a.gpg", "a (1).gpg", "b.gpg", "a (2).gpg"),
            FileBatchNaming.uniqueWithin(listOf("a.gpg", "a.gpg", "b.gpg", "a.gpg"))
        )
    }

    @Test
    fun decryptedName_stripsTheExtensionBeforeACopyNumber() {
        assertEquals("message (8)", FileBatchNaming.decryptedName("message.asc (8)", null))
        assertEquals("report.pdf (2)", FileBatchNaming.decryptedName("report.pdf.GPG (2)", null))
    }

    @Test
    fun withContentExtension_namesTextAndKnownFormats() {
        assertEquals("message.txt", FileBatchNaming.withContentExtension("message", "hello".toByteArray()))
        assertEquals("message (8).txt", FileBatchNaming.withContentExtension("message (8)", "hi\n".toByteArray()))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("scan.png", FileBatchNaming.withContentExtension("scan", png))
        assertEquals("blob", FileBatchNaming.withContentExtension("blob", byteArrayOf(0, 1, 2, 0xFF.toByte())))
        assertEquals("notes.md", FileBatchNaming.withContentExtension("notes.md", "x".toByteArray()))
        assertEquals(".profile.txt", FileBatchNaming.withContentExtension(".profile", "x".toByteArray()))
    }
}
