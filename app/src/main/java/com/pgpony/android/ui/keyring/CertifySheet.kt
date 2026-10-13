// CertifySheet.kt
// PGPony Android 4.7.0 (item 31, #79): certify another person's User IDs with
// one of your key pairs, like gpg's --sign-key (or --lsign-key when Local
// only is on). The full fingerprint is shown and must be confirmed as
// compared before Certify enables; the level follows gpg's --ask-cert-level.

package com.pgpony.android.ui.keyring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pgpony.android.R
import com.pgpony.android.crypto.CertificationService
import com.pgpony.android.data.PGPKeyEntity

/** How long a certification stays valid. */
enum class CertifyExpiry(val seconds: Long?) {
    NEVER(null),
    ONE_YEAR(365L * 24 * 3600),
    TWO_YEARS(2 * 365L * 24 * 3600),
    FIVE_YEARS(5 * 365L * 24 * 3600)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CertifySheet(
    formattedFingerprint: String,
    userIds: List<KeyUserIdInfo>,
    certifiers: List<PGPKeyEntity>,
    isProcessing: Boolean = false,
    errorMessage: String? = null,
    onApply: (
        certifierFingerprint: String,
        passphrase: String?,
        userIds: List<String>,
        level: CertificationService.Level,
        expirySeconds: Long?,
        exportable: Boolean
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val certifiable = userIds.filter { !it.isRevoked }
    var picked by remember { mutableStateOf(certifiable.map { it.raw }.toSet()) }
    var certifier by remember { mutableStateOf(certifiers.firstOrNull { it.isDefault } ?: certifiers.firstOrNull()) }
    var certifierMenu by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(CertificationService.Level.NO_CLAIM) }
    var expiry by remember { mutableStateOf(CertifyExpiry.NEVER) }
    var localOnly by remember { mutableStateOf(false) }
    var compared by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }

    val canApply = !isProcessing && certifier != null && picked.isNotEmpty() && compared

    ModalBottomSheet(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.certify_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.certify_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (certifiers.isEmpty()) {
                Text(
                    stringResource(R.string.certify_no_certifier),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_button_close))
                }
                return@Column
            }

            Text(stringResource(R.string.certify_userids_label), style = MaterialTheme.typography.titleSmall)
            certifiable.forEach { uid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uid.raw in picked,
                        onCheckedChange = { on -> picked = if (on) picked + uid.raw else picked - uid.raw },
                        enabled = !isProcessing
                    )
                    Text(uid.raw, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(stringResource(R.string.certify_certifier_label), style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(
                    onClick = { certifierMenu = true },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        certifier?.let { certifierLabel(it) } ?: "",
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
                DropdownMenu(expanded = certifierMenu, onDismissRequest = { certifierMenu = false }) {
                    certifiers.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(certifierLabel(c)) },
                            onClick = { certifier = c; certifierMenu = false }
                        )
                    }
                }
            }

            Text(stringResource(R.string.certify_level_label), style = MaterialTheme.typography.titleSmall)
            CertificationService.Level.entries.forEach { l ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(selected = level == l, enabled = !isProcessing) { level = l }
                ) {
                    RadioButton(selected = level == l, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(certifyLevelLabel(l), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(stringResource(R.string.certify_expiry_label), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CertifyExpiry.entries.forEach { e ->
                    FilterChip(
                        selected = expiry == e,
                        onClick = { expiry = e },
                        enabled = !isProcessing,
                        label = {
                            Text(
                                stringResource(
                                    when (e) {
                                        CertifyExpiry.NEVER -> R.string.expiration_never
                                        CertifyExpiry.ONE_YEAR -> R.string.expiration_one_year
                                        CertifyExpiry.TWO_YEARS -> R.string.expiration_two_years
                                        CertifyExpiry.FIVE_YEARS -> R.string.expiration_five_years
                                    }
                                )
                            )
                        }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.certify_local_only_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.certify_local_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = localOnly, onCheckedChange = { localOnly = it }, enabled = !isProcessing)
            }

            Text(stringResource(R.string.import_preview_detail_fingerprint_label), style = MaterialTheme.typography.titleSmall)
            Text(formattedFingerprint, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = compared, onCheckedChange = { compared = it }, enabled = !isProcessing)
                Text(stringResource(R.string.certify_fingerprint_confirm), style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.key_detail_add_userid_passphrase_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !isProcessing, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_button_cancel))
                }
                Button(
                    onClick = {
                        val c = certifier ?: return@Button
                        onApply(
                            c.fingerprint,
                            passphrase.ifBlank { null },
                            certifiable.map { it.raw }.filter { it in picked },
                            level,
                            expiry.seconds,
                            !localOnly
                        )
                    },
                    enabled = canApply,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.certify_apply))
                    }
                }
            }
        }
    }
}

private fun certifierLabel(key: PGPKeyEntity): String =
    "${key.userName.ifBlank { key.userEmail.ifBlank { key.userID } }} (${key.shortFingerprint})"

@Composable
fun certifyLevelLabel(level: CertificationService.Level): String = stringResource(
    when (level) {
        CertificationService.Level.NO_CLAIM -> R.string.certify_level_no_claim
        CertificationService.Level.PERSONA -> R.string.certify_level_persona
        CertificationService.Level.CASUAL -> R.string.certify_level_casual
        CertificationService.Level.POSITIVE -> R.string.certify_level_positive
    }
)
