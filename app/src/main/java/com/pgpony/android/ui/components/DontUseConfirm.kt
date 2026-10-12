// DontUseConfirm.kt
// PGPony Android 4.7.0 (item 10, #75): the confirmation shown before
// encrypting to a key marked Don't use, or signing with one. Decrypting and
// verifying never ask; they keep working and show the red mark.

package com.pgpony.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pgpony.android.R
import com.pgpony.android.data.PGPKeyEntity
import com.pgpony.android.data.TrustLevel

/** The keys in [keys] marked Don't use, in order, each once. */
fun dontUseKeys(keys: Collection<PGPKeyEntity?>): List<PGPKeyEntity> =
    keys.filterNotNull().filter { it.trustLevel == TrustLevel.DONT_USE }.distinctBy { it.fingerprint }

/** The name a Don't use prompt shows for [key]. */
fun dontUseLabel(key: PGPKeyEntity): String =
    key.userName.ifBlank { key.userEmail.ifBlank { key.shortFingerprint } }

@Composable
fun DontUseConfirmDialog(
    keys: List<PGPKeyEntity>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { TrustMark(trust = TrustLevel.DONT_USE, size = 28.dp, contentDescription = null) },
        title = { Text(stringResource(R.string.dont_use_confirm_title)) },
        text = {
            Text(stringResource(R.string.dont_use_confirm_body, keys.joinToString(", ") { dontUseLabel(it) }))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.dont_use_confirm_continue), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_button_cancel)) }
        }
    )
}
