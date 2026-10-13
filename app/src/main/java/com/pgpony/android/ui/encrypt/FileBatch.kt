// FileBatch.kt
// PGPony Android 4.7.0 (items 15 and 38, #72): several files at once.
//
// Item 15: with several files chosen for encryption, the user can make one
// package (the existing bundle) or handle each file on its own: encrypted,
// encrypted and signed, or signed only with a detached .sig per file.
// Item 38: several encrypted files shared into PGPony (or picked on the
// Decrypt screen) decrypt and verify one by one instead of being wrapped
// into a new package.
//
// One failed file never stops the rest, so the state is a list of items,
// each with its own status. The outputs sit in ScratchFiles.SCOPE_BATCH
// until the user saves them to a folder or shares them together.

package com.pgpony.android.ui.encrypt

import com.pgpony.android.crypto.LiteralFilename
import com.pgpony.android.crypto.VerificationResult

/** 4.7.0 (item 15, #72): what Bundle mode does with its files. */
enum class BundleOutputMode { PACKAGE, EACH_FILE }

/** 4.7.0 (item 15, #72): the per-file operation when [BundleOutputMode.EACH_FILE]. */
enum class PerFileOp { ENCRYPT, ENCRYPT_AND_SIGN, SIGN_ONLY }

/** 4.7.0 (items 15 and 38): which operation a batch runs. */
enum class FileBatchKind { ENCRYPT, ENCRYPT_AND_SIGN, SIGN_ONLY, DECRYPT }

sealed class FileBatchStatus {
    data object Pending : FileBatchStatus()
    data object Running : FileBatchStatus()
    data object Done : FileBatchStatus()
    data class Failed(val message: String) : FileBatchStatus()
}

data class FileBatchItem(
    val inputName: String,
    val inputSize: Long,
    /** Planned output name; for decrypt it is set once the file is read. */
    val outputName: String? = null,
    val output: java.io.File? = null,
    val status: FileBatchStatus = FileBatchStatus.Pending,
    /** Decrypt only: the signature result for this file. */
    val verification: VerificationResult? = null,
    /** Name the file got in the folder the user picked, after a save. */
    val savedAs: String? = null,
    val saveError: String? = null,
)

data class FileBatchState(
    val kind: FileBatchKind,
    val items: List<FileBatchItem>,
    val running: Boolean = false,
    /** The batch is paused until a passphrase is entered (asked once). */
    val needsPassphrase: Boolean = false,
    val passphraseError: String? = null,
    val saving: Boolean = false,
    val saveSummary: String? = null,
) {
    val doneCount: Int get() = items.count { it.status is FileBatchStatus.Done }
    val failedCount: Int get() = items.count { it.status is FileBatchStatus.Failed }
    val finished: Boolean
        get() = !running && !needsPassphrase &&
            items.none { it.status is FileBatchStatus.Pending || it.status is FileBatchStatus.Running }
}

/**
 * 4.7.0 (items 15 and 38, #72): output names for a batch. Pure, so it is unit
 * tested (FileBatchNamingTest). Every name that can come from outside (a
 * shared file's display name, a literal-data packet's filename) goes through
 * LiteralFilename.sanitize first.
 */
internal object FileBatchNaming {

    private val PGP_EXTENSIONS = listOf(".asc", ".gpg", ".pgp")

    /** Suffixes PGPony adds; a collision number goes before the name's own
     *  extension instead ("photo (1).jpg.gpg"), so decrypting it gives back
     *  "photo (1).jpg". */
    private val ADDED_SUFFIXES = PGP_EXTENSIONS + ".sig"

    private fun base(input: String?): String = LiteralFilename.sanitize(input) ?: "file"

    /** "report.pdf" becomes "report.pdf.gpg" (binary, like single-file mode). */
    fun encryptedName(input: String?): String = "${base(input)}.gpg"

    /** "report.pdf" becomes "report.pdf.sig" (binary detached signature). */
    fun signatureName(input: String?): String = "${base(input)}.sig"

    /** [name] without a trailing .asc, .gpg or .pgp (any case), or null when
     *  it has none or nothing would be left. */
    fun stripPgpExtension(name: String): String? {
        val lower = name.lowercase()
        for (ext in PGP_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return name.dropLast(ext.length).takeIf { it.isNotBlank() }
            }
        }
        // 4.7.0 (item 38): a downloaded copy such as "message.asc (8)" keeps
        // its copy number after the extension; strip the extension and keep
        // the number, "message (8)".
        COPY_SUFFIXED.matchEntire(name)?.let { m ->
            val stem = m.groupValues[1]
            if (stem.isNotBlank()) return stem + m.groupValues[3]
        }
        return null
    }

    private val COPY_SUFFIXED = Regex("""(.*)\.(asc|gpg|pgp)( \(\d+\))""", RegexOption.IGNORE_CASE)

    /** [name] has an extension of its own: a dot followed by 1 to 8 letters
     *  or digits, not at the start (a hidden file like ".profile" has none). */
    fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1)
        return ext.length <= 8 && ext.all { it.isLetterOrDigit() }
    }

    /**
     * 4.7.0 (item 38): a decrypted file whose name has no extension (no
     * literal filename, and the shared name was only "message.asc") gets one
     * from its content, so it opens: ".txt" for text, or the image, PDF or
     * archive type PlaintextKind recognises. [head] is the start of the
     * plaintext; a name that already has an extension is left alone.
     */
    fun withContentExtension(name: String, head: ByteArray): String {
        if (hasExtension(name)) return name
        if (com.pgpony.android.intent.PlaintextKind.isText(head)) return "$name.txt"
        return com.pgpony.android.intent.PlaintextKind.extensionFor(head)?.let { "$name.$it" } ?: name
    }

    /**
     * The name a decrypted file is saved under: the literal-data packet's
     * filename when the message carries one (gpg's "_CONSOLE" marker means
     * none), else the shared file's name without .asc, .gpg or .pgp, else
     * "decrypted_" plus that name.
     */
    fun decryptedName(inputName: String?, literalName: String?): String {
        val literal = LiteralFilename.sanitize(literalName)
            ?.takeUnless { it.equals("_CONSOLE", ignoreCase = true) }
        if (literal != null) return literal
        val input = LiteralFilename.sanitize(inputName) ?: return "decrypted_output"
        stripPgpExtension(input)?.let { stripped ->
            LiteralFilename.sanitize(stripped)?.let { return it }
        }
        return "decrypted_$input"
    }

    /**
     * [desired], or "stem (n).ext" with the smallest n that is free, compared
     * without case (Android's shared storage and most cloud folders ignore
     * case). Hidden-file names like ".profile" keep the whole name as stem.
     */
    fun uniqueName(desired: String, taken: Set<String>): String {
        val lowerTaken = taken.mapTo(HashSet()) { it.lowercase() }
        if (desired.lowercase() !in lowerTaken) return desired
        val (stem, ext) = splitForNumber(desired)
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in lowerTaken) return candidate
            n++
        }
    }

    private fun splitForNumber(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        val ext = name.substring(dot)
        val stem = name.substring(0, dot)
        if (ext.lowercase() in ADDED_SUFFIXES) {
            val inner = stem.lastIndexOf('.')
            if (inner > 0) return stem.substring(0, inner) to (stem.substring(inner) + ext)
        }
        return stem to ext
    }

    /** Output names for a whole batch, made unique within it. */
    fun uniqueWithin(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { n ->
            val u = uniqueName(n, taken)
            taken.add(u)
            u
        }
    }
}
