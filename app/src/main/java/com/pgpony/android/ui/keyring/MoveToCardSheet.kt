// MoveToCardSheet.kt
// PGPony Android, 4.7.0 RC4 (item 13, #71): Move to Security Key, gpg's
// keytocard. Pick which key goes to each slot of the card (gpg style: a
// signing key to Signature, an encryption key to Decryption, an
// authentication key to Authentication), confirm a backup exists, choose
// whether the moved keys leave the phone, and give the key's passphrase, the
// card's admin PIN (to write) and user PIN (to check each slot afterwards).

package com.pgpony.android.ui.keyring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pgpony.android.R
import com.pgpony.android.crypto.card.CardSlot
import com.pgpony.android.crypto.card.KeyToCard

/**
 * The key a slot gets by default: the newest key that fits it, a subkey before
 * the primary. The Authentication slot starts empty: it is only filled when
 * the owner picks a key for it.
 */
internal fun defaultMoveChoice(candidates: List<MoveToCardCandidate>, slot: CardSlot): MoveToCardCandidate? {
    if (slot == CardSlot.AUTHENTICATION) return null
    return candidates.filter { it.fits(slot) }
        .sortedWith(compareBy<MoveToCardCandidate>({ it.isPrimary }, { -it.createdAt }))
        .firstOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToCardSheet(
    candidates: List<MoveToCardCandidate>,
    /** Keys of this key pair with their secret on the phone. */
    secretCount: Int,
    passphraseProtected: Boolean,
    isProcessing: Boolean,
    errorMessage: String?,
    onBackup: () -> Unit,
    onMove: (
        choices: Map<CardSlot, String>,
        passphrase: String?,
        adminPin: String,
        userPin: String,
        removeFromPhone: Boolean,
        replace: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val choices = remember(candidates) {
        mutableStateMapOf<CardSlot, String?>().apply {
            CardSlot.entries.forEach { put(it, defaultMoveChoice(candidates, it)?.fingerprint) }
        }
    }
    var haveBackup by remember { mutableStateOf(false) }
    var removeFromPhone by remember { mutableStateOf(true) }
    var replaceSlots by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var adminPin by remember { mutableStateOf("") }
    var userPin by remember { mutableStateOf("") }

    val picked = choices.filterValues { it != null }.mapValues { it.value!! }
    val distinct = picked.values.toSet().size == picked.size
    // The moved keys leave the phone only when every key with a secret here
    // moves; otherwise the phone keeps working with its copy.
    val removeAllowed = distinct && picked.size == secretCount
    // Replacing is not a gate: the card is read before any PIN is sent, and a
    // chosen slot holding a different key stops the move unless this is on.
    val canMove = !isProcessing && picked.isNotEmpty() && distinct && haveBackup &&
        adminPin.isNotEmpty() && userPin.isNotEmpty() && (!passphraseProtected || passphrase.isNotEmpty())

    // A long form holding a passphrase and two PINs: no swipe to dismiss (it
    // fired while scrolling). Cancel, Back or a tap outside still close it.
    ModalBottomSheet(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.move_to_card_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.move_to_card_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (candidates.none { it.kind != null }) {
                Text(
                    stringResource(R.string.move_to_card_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_button_close))
                }
                RefusedList(candidates)
                return@Column
            }

            CardSlot.entries.forEach { slot ->
                SlotChoice(
                    slot = slot,
                    options = candidates.filter { it.fits(slot) },
                    selected = choices[slot],
                    enabled = !isProcessing,
                    onSelect = { choices[slot] = it }
                )
            }
            if (!distinct) {
                Text(
                    stringResource(R.string.move_to_card_one_slot_each),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            RefusedList(candidates)

            HorizontalDivider()
            Text(
                stringResource(R.string.move_to_card_backup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onBackup, enabled = !isProcessing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.move_to_card_backup_action))
            }
            CheckRow(stringResource(R.string.move_to_card_backup_confirm), haveBackup, !isProcessing) { haveBackup = it }
            SwitchRow(
                stringResource(R.string.move_to_card_remove_label),
                stringResource(if (removeAllowed) R.string.move_to_card_remove_hint else R.string.move_to_card_remove_unavailable),
                removeFromPhone && removeAllowed, !isProcessing && removeAllowed
            ) { removeFromPhone = it }
            CheckRow(stringResource(R.string.move_to_card_replace_confirm), replaceSlots, !isProcessing) { replaceSlots = it }

            if (passphraseProtected) {
                SecretField(stringResource(R.string.key_detail_add_userid_passphrase_label), passphrase, !isProcessing) { passphrase = it }
            }
            SecretField(stringResource(R.string.move_to_card_admin_pin), adminPin, !isProcessing) { adminPin = it }
            SecretField(stringResource(R.string.move_to_card_user_pin), userPin, !isProcessing) { userPin = it }
            Text(
                stringResource(R.string.move_to_card_pins_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (errorMessage != null) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !isProcessing, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_button_cancel))
                }
                Button(
                    onClick = { onMove(picked, passphrase.ifEmpty { null }, adminPin, userPin, removeFromPhone && removeAllowed, replaceSlots) },
                    enabled = canMove,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.move_to_card_action))
                    }
                }
            }
            if (isProcessing) {
                Text(
                    stringResource(R.string.move_to_card_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun slotName(slot: CardSlot): String = stringResource(
    when (slot) {
        CardSlot.SIGNATURE -> R.string.move_to_card_slot_signature
        CardSlot.DECRYPTION -> R.string.move_to_card_slot_decryption
        CardSlot.AUTHENTICATION -> R.string.move_to_card_slot_authentication
    }
)

@Composable
private fun candidateLabel(c: MoveToCardCandidate): String = stringResource(
    if (c.isPrimary) R.string.move_to_card_key_primary_format else R.string.move_to_card_key_subkey_format,
    c.algorithmLabel, c.keyIdHex
)

@Composable
private fun SlotChoice(
    slot: CardSlot,
    options: List<MoveToCardCandidate>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Text(slotName(slot), style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                options.firstOrNull { it.fingerprint == selected }?.let { candidateLabel(it) }
                    ?: stringResource(R.string.move_to_card_slot_leave),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_card_slot_leave)) },
                onClick = { onSelect(null); open = false }
            )
            options.forEach { c ->
                DropdownMenuItem(text = { Text(candidateLabel(c)) }, onClick = { onSelect(c.fingerprint); open = false })
            }
        }
    }
}

@Composable
private fun RefusedList(candidates: List<MoveToCardCandidate>) {
    val refused = candidates.filter { it.refusal != null }
    if (refused.isEmpty()) return
    Text(
        stringResource(R.string.move_to_card_refused_title),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    refused.forEach { c ->
        Text(
            candidateLabel(c) + ": " + stringResource(
                when (c.refusal) {
                    KeyToCard.Refusal.NOT_V4 -> R.string.move_to_card_refused_v6
                    KeyToCard.Refusal.RSA_SIZE -> R.string.move_to_card_refused_rsa_size
                    KeyToCard.Refusal.CURVE -> R.string.move_to_card_refused_curve
                    else -> R.string.move_to_card_refused_algorithm
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.minimumInteractiveComponentSize())
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

@Composable
private fun SecretField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}
