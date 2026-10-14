// KeyLines.kt
// PGPony Android, 4.7.0 (item 48, #79)
//
// The wording for the key lines on decrypt and verify results: which key
// made a signature and which key decrypted, by 16-hex key ID, so two keys
// with the same name, or a fallback key that decrypted instead of the one
// picked, can be told apart.

package com.pgpony.android.ui.decrypt

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pgpony.android.R
import com.pgpony.android.crypto.KeyIdText

/** "Signing key X", or "Signing subkey X of key Y" when a subkey signed. */
@Composable
fun signingKeyLineText(line: KeyIdText.Line): String =
    if (line.primaryKeyId != null) {
        stringResource(R.string.verify_signing_subkey_format, line.keyId, line.primaryKeyId)
    } else {
        stringResource(R.string.verify_signing_key_format, line.keyId)
    }

/**
 * "Decrypted with NAME", plus the subkey when a subkey (not the primary)
 * decrypted. [label] already carries the key pair's short fingerprint.
 */
@Composable
fun decryptedByText(label: String, line: KeyIdText.Line?): String =
    if (line?.primaryKeyId != null) {
        stringResource(R.string.encdec_decrypted_with_subkey_format, label, line.keyId)
    } else {
        stringResource(R.string.encdec_decrypted_with_format, label)
    }
