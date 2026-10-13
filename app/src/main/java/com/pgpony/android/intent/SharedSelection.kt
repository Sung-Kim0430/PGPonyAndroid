// SharedSelection.kt
// PGPony Android 4.7.0 (item 38, #72): a share of several files used to go
// straight to package (bundle) encrypt, whatever the files were, so several
// encrypted .asc files came back wrapped in one new encrypted package. Each
// shared file is now classified from its head (never the whole file) and the
// selection is routed as a whole: all encrypted goes to decrypt, a mix asks,
// plain files keep the package route.
//
// Pure logic on purpose, so the routing can be unit tested without a
// ContentResolver. IntentHandler reads the heads and supplies the
// BouncyCastle recipient check as [classify]'s fallback.

package com.pgpony.android.intent

internal object SharedSelection {

    /** How much of each shared file is read to classify it. */
    const val HEAD_BYTES = 64 * 1024

    /** Only the first part of the head is decoded as text for armor markers. */
    private const val TEXT_SNIFF_BYTES = 1024

    enum class Kind {
        /** An encrypted OpenPGP message, armored, binary or in an RFC 3156 envelope. */
        ENCRYPTED,
        /** A standalone detached signature, armored or binary. */
        DETACHED_SIGNATURE,
        /** Anything else: plain files, keys, clear-signed text. */
        OTHER
    }

    enum class Route {
        /** Every file is an encrypted message: decrypt each one. */
        DECRYPT_ALL,
        /** Encrypted messages mixed with other files: let the user choose. */
        ASK,
        /** No encrypted messages: the package (bundle) route, as before. */
        PACKAGE
    }

    /**
     * Classify one shared file from [head], its first bytes. [complete] is
     * true when [head] is the whole file, which a binary detached signature
     * needs (its packet walk must reach the end). [bcSaysEncrypted] is the
     * BouncyCastle recipient listing, tried last for binary input.
     */
    fun classify(
        head: ByteArray,
        complete: Boolean,
        bcSaysEncrypted: (ByteArray) -> Boolean = { false }
    ): Kind {
        if (head.isEmpty()) return Kind.OTHER
        val text = try {
            String(head, 0, minOf(head.size, TEXT_SNIFF_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
        if (text.contains("multipart/encrypted", ignoreCase = true)) return Kind.ENCRYPTED
        if (text.contains("-----BEGIN PGP")) {
            return when (ClearSignedInput.firstArmorLine(text)) {
                "-----BEGIN PGP MESSAGE-----" -> Kind.ENCRYPTED
                "-----BEGIN PGP SIGNATURE-----" -> Kind.DETACHED_SIGNATURE
                else -> Kind.OTHER
            }
        }
        if (complete && PacketSniff.isDetachedSignature(head)) return Kind.DETACHED_SIGNATURE
        if (PacketSniff.looksLikeEncryptedMessage(head)) return Kind.ENCRYPTED
        if (PacketSniff.isKnownNonOpenPgp(head)) return Kind.OTHER
        return if (bcSaysEncrypted(head)) Kind.ENCRYPTED else Kind.OTHER
    }

    /** Route a whole selection by the kinds of its files. */
    fun route(kinds: List<Kind>): Route {
        if (kinds.isEmpty()) return Route.PACKAGE
        val encrypted = kinds.count { it == Kind.ENCRYPTED }
        return when {
            encrypted == kinds.size -> Route.DECRYPT_ALL
            encrypted > 0 -> Route.ASK
            else -> Route.PACKAGE
        }
    }
}
