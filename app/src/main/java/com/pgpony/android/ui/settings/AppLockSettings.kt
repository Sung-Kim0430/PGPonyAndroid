// AppLockSettings.kt
// PGPony Android 4.7.0 (item 32): Settings for the PGPony PIN app lock.
//
// Security > Lock method: device unlock (as before) or a PGPony PIN. With a PIN
// set: change it, an optional duress PIN, fingerprint as an alternative, and an
// optional wipe after a number of wrong PINs (off by default). Changing any of
// these asks for the current PIN once per visit, through the same check as the
// lock screen, so a duress PIN entered here wipes the same way.
//
// Self-contained like CardPinCacheSection: reads and writes AppLock directly.

package com.pgpony.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pgpony.android.R
import com.pgpony.android.lock.AppLock
import com.pgpony.android.lock.AppLockConfig
import com.pgpony.android.lock.PinFormat
import com.pgpony.android.lock.PinRules
import com.pgpony.android.lock.PinSetup
import com.pgpony.android.ui.components.PinAttemptUi
import com.pgpony.android.ui.components.PinTextField
import com.pgpony.android.ui.components.submitPinAttempt
import com.pgpony.android.ui.keyring.BiometricAvailability
import com.pgpony.android.ui.keyring.BiometricGate
import kotlinx.coroutines.launch

private enum class LockDialog {
    METHOD, SET_PIN, CHANGE_PIN, VERIFY, DURESS_INFO, DURESS_SET, DURESS_REMOVE, FINGERPRINT_ADVICE, WIPE_AFTER
}

private val LockTint = Color(0xFF8B5CF6)

/**
 * [deviceLockOn] and [onSetDeviceLock] are the existing device-unlock switch
 * (biometric_lock); [pinLockOn] is whether a PGPony PIN is set, hoisted so
 * SettingsScreen can hide that switch while the PIN is the lock.
 */
@Composable
internal fun AppLockSection(
    deviceLockOn: Boolean,
    onSetDeviceLock: (Boolean) -> Unit,
    pinLockOn: Boolean,
    onPinLockChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c != null && c !is android.app.Activity) {
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        c as? android.app.Activity
    }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<AppLockConfig?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(pinLockOn, reload) {
        config = if (pinLockOn) AppLock.load(context) else null
    }
    // One current-PIN check per visit unlocks the changes below, and no
    // longer than until the app locks: Settings stays composed under the lock
    // overlay, so the check is keyed on AppLock.lockEpoch.
    val lockEpoch = AppLock.lockEpoch.intValue
    var verified by remember(lockEpoch) { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dialog by remember { mutableStateOf<LockDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A lock also closes whatever the check had opened.
    LaunchedEffect(lockEpoch) {
        dialog = null
        pendingAction = null
        busy = false
        error = null
    }

    val open: (LockDialog) -> Unit = { d -> error = null; dialog = d }
    val close: () -> Unit = { error = null; busy = false; dialog = null }
    val requireVerified: (() -> Unit) -> Unit = { action ->
        if (verified) {
            action()
        } else {
            pendingAction = action
            open(LockDialog.VERIFY)
        }
    }
    val formatError = stringResource(R.string.applock_pin_format_error)
    val mismatchError = stringResource(R.string.applock_pin_mismatch)
    val biometricReady = remember(context) {
        BiometricGate.canAuthenticateBiometricOnly(context) == BiometricAvailability.Available
    }

    // Shared handling of a set or change result.
    val onSetup: (PinSetup, String, () -> Unit) -> Unit = { setup, sameMessage, onDone ->
        busy = false
        when (setup) {
            is PinSetup.Done -> onDone()
            is PinSetup.Invalid -> error = formatError
            PinSetup.SameAsOther -> error = sameMessage
            PinSetup.Unsupported -> error = context.getString(R.string.applock_unavailable)
        }
    }
    // Validate a new PIN and its confirmation before any hashing.
    val validPair: (String, String) -> Boolean = { first, second ->
        when {
            PinRules.check(first) != PinFormat.OK -> { error = formatError; false }
            first != second -> { error = mismatchError; false }
            else -> true
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────
    LockActionRow(
        title = stringResource(R.string.applock_method_title),
        subtitle = stringResource(if (pinLockOn) R.string.applock_method_pin else R.string.applock_method_device),
        icon = Icons.Filled.Password,
        onClick = { open(LockDialog.METHOD) }
    )
    if (pinLockOn) {
        val cfg = config
        LockActionRow(
            title = stringResource(R.string.applock_change_pin_title),
            subtitle = stringResource(R.string.applock_change_pin_subtitle),
            icon = Icons.Filled.Password,
            onClick = { requireVerified { open(LockDialog.CHANGE_PIN) } }
        )
        LockActionRow(
            title = stringResource(R.string.applock_duress_title),
            subtitle = stringResource(if (cfg?.hasDuress == true) R.string.applock_duress_on else R.string.applock_duress_off),
            icon = Icons.Filled.Warning,
            onClick = {
                if (cfg?.hasDuress == true) {
                    requireVerified { open(LockDialog.DURESS_REMOVE) }
                } else {
                    requireVerified { open(LockDialog.DURESS_INFO) }
                }
            }
        )
        LockToggleRow(
            title = stringResource(R.string.applock_fingerprint_title),
            subtitle = stringResource(R.string.applock_fingerprint_subtitle),
            icon = Icons.Filled.Fingerprint,
            checked = cfg?.fingerprintAllowed == true,
            onCheckedChange = { target ->
                if (target) {
                    requireVerified {
                        if (BiometricGate.canAuthenticateBiometricOnly(context) == BiometricAvailability.Available) {
                            scope.launch {
                                // A fresh key, bound to the fingerprints enrolled now.
                                if (!AppLock.setFingerprintAllowed(context, true)) {
                                    Toast.makeText(context, context.getString(R.string.lock_error_biometric_unavailable), Toast.LENGTH_LONG).show()
                                }
                                reload++
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.lock_error_biometric_unavailable), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    // Turning it off only tightens the lock: no PIN needed.
                    scope.launch { AppLock.setFingerprintAllowed(context, false); reload++ }
                }
            }
        )
        val wipeAfter = cfg?.wipeAfter ?: 0
        LockActionRow(
            title = stringResource(R.string.applock_wipe_after_title),
            subtitle = if (wipeAfter > 0) stringResource(R.string.applock_wipe_after_format, wipeAfter)
                else stringResource(R.string.applock_wipe_after_off),
            icon = Icons.Filled.Delete,
            onClick = { requireVerified { open(LockDialog.WIPE_AFTER) } }
        )
    }

    // ── Dialogs ─────────────────────────────────────────────────────────
    when (dialog) {
        null -> {}
        LockDialog.METHOD -> ChoiceDialog(
            title = stringResource(R.string.applock_method_title),
            message = stringResource(R.string.applock_method_dialog_message),
            options = listOf(
                stringResource(R.string.applock_method_device),
                stringResource(R.string.applock_method_pin)
            ),
            selected = if (pinLockOn) 1 else 0,
            onSelect = { index ->
                when {
                    index == 1 && !pinLockOn -> open(LockDialog.SET_PIN)
                    index == 0 && pinLockOn -> requireVerified {
                        AppLock.clear(context)
                        verified = false
                        close()
                        onPinLockChanged(false)
                        onSetDeviceLock(true)
                    }
                    else -> close()
                }
            },
            onDismiss = close
        )
        LockDialog.SET_PIN -> PinPairDialog(
            title = stringResource(R.string.applock_set_pin_title),
            message = stringResource(R.string.applock_set_pin_message),
            firstLabel = stringResource(R.string.applock_pin_field_label),
            busy = busy,
            error = error,
            onSubmit = { first, second ->
                if (validPair(first, second)) {
                    busy = true
                    // Keep the fingerprint when it was already the way in.
                    val keepFingerprint = deviceLockOn && biometricReady
                    scope.launch {
                        val r = AppLock.create(context, first, fingerprintAllowed = keepFingerprint)
                        onSetup(r, formatError) {
                            verified = true
                            close()
                            onPinLockChanged(true)
                            reload++
                        }
                    }
                }
            },
            onDismiss = close
        )
        LockDialog.CHANGE_PIN -> PinPairDialog(
            title = stringResource(R.string.applock_change_pin_title),
            message = null,
            firstLabel = stringResource(R.string.applock_new_pin_label),
            busy = busy,
            error = error,
            onSubmit = { first, second ->
                if (validPair(first, second)) {
                    busy = true
                    scope.launch {
                        val r = AppLock.changePin(context, first)
                        onSetup(r, context.getString(R.string.applock_pin_same_as_duress)) {
                            close()
                            reload++
                        }
                    }
                }
            },
            onDismiss = close
        )
        LockDialog.VERIFY -> PinSingleDialog(
            title = stringResource(R.string.applock_verify_title),
            message = stringResource(R.string.applock_verify_message),
            busy = busy,
            error = error,
            onSubmit = { pin ->
                if (PinRules.check(pin) != PinFormat.OK) {
                    error = context.getString(R.string.applock_wrong_pin)
                } else {
                    busy = true
                    error = null
                    submitPinAttempt(context, activity, pin) { result ->
                        busy = false
                        when (result) {
                            PinAttemptUi.Unlocked -> {
                                verified = true
                                val next = pendingAction
                                pendingAction = null
                                close()
                                next?.invoke()
                            }
                            PinAttemptUi.Wrong -> error = context.getString(R.string.applock_wrong_pin)
                            is PinAttemptUi.Wait -> error = context.getString(
                                R.string.applock_wait_format, ((result.remainingMs + 999L) / 1000L).toInt()
                            )
                            PinAttemptUi.Unavailable -> error = context.getString(R.string.applock_unavailable)
                            PinAttemptUi.Failed -> error = context.getString(R.string.applock_check_failed)
                        }
                    }
                }
            },
            onDismiss = { pendingAction = null; close() }
        )
        LockDialog.DURESS_INFO -> MessageDialog(
            title = stringResource(R.string.applock_duress_info_title),
            message = stringResource(R.string.applock_duress_info_message) + "\n\n" +
                stringResource(R.string.applock_not_touched),
            confirm = stringResource(R.string.common_button_continue),
            onConfirm = { open(LockDialog.DURESS_SET) },
            dismiss = stringResource(R.string.common_button_cancel),
            onDismiss = close
        )
        LockDialog.DURESS_SET -> PinPairDialog(
            title = stringResource(R.string.applock_duress_set_title),
            message = null,
            firstLabel = stringResource(R.string.applock_pin_field_label),
            busy = busy,
            error = error,
            onSubmit = { first, second ->
                if (validPair(first, second)) {
                    busy = true
                    scope.launch {
                        val r = AppLock.setDuress(context, first)
                        onSetup(r, context.getString(R.string.applock_duress_same_as_pin)) {
                            reload++
                            // A finger can be forced: recommend, not force, turning it off.
                            if (config?.fingerprintAllowed == true) open(LockDialog.FINGERPRINT_ADVICE) else close()
                        }
                    }
                }
            },
            onDismiss = close
        )
        LockDialog.DURESS_REMOVE -> MessageDialog(
            title = stringResource(R.string.applock_duress_remove_title),
            message = stringResource(R.string.applock_duress_remove_message),
            confirm = stringResource(R.string.applock_duress_remove_button),
            onConfirm = {
                scope.launch {
                    AppLock.removeDuress(context)
                    reload++
                    close()
                }
            },
            dismiss = stringResource(R.string.common_button_cancel),
            onDismiss = close
        )
        LockDialog.FINGERPRINT_ADVICE -> MessageDialog(
            title = stringResource(R.string.applock_fingerprint_advice_title),
            message = stringResource(R.string.applock_fingerprint_advice_message),
            confirm = stringResource(R.string.applock_fingerprint_advice_turn_off),
            onConfirm = {
                scope.launch {
                    AppLock.setFingerprintAllowed(context, false)
                    reload++
                    close()
                }
            },
            dismiss = stringResource(R.string.applock_fingerprint_advice_keep),
            onDismiss = close
        )
        LockDialog.WIPE_AFTER -> {
            val choices = PinRules.WIPE_AFTER_CHOICES
            val current = config?.wipeAfter ?: 0
            ChoiceDialog(
                title = stringResource(R.string.applock_wipe_after_title),
                message = stringResource(R.string.applock_wipe_after_message) + "\n\n" +
                    stringResource(R.string.applock_not_touched),
                options = choices.map { n ->
                    if (n == 0) stringResource(R.string.applock_wipe_after_off)
                    else stringResource(R.string.applock_wipe_after_format, n)
                },
                selected = choices.indexOf(current).coerceAtLeast(0),
                onSelect = { index ->
                    val n = choices[index]
                    scope.launch {
                        AppLock.setWipeAfter(context, n)
                        reload++
                        close()
                    }
                },
                onDismiss = close
            )
        }
    }
}

// ── Rows, styled like SettingsScreen's private SettingsAction / SettingsToggle ──

@Composable
private fun LockActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = LockTint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Filled.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LockToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = LockTint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────

@Composable
private fun ErrorLine(error: String?) {
    if (error != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PinPairDialog(
    title: String,
    message: String?,
    firstLabel: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val canSubmit = !busy && first.isNotEmpty() && second.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                PinTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = firstLabel,
                    enabled = !busy,
                    imeAction = ImeAction.Next
                )
                Spacer(modifier = Modifier.height(8.dp))
                PinTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = stringResource(R.string.applock_pin_confirm_label),
                    enabled = !busy,
                    onDone = { if (canSubmit) onSubmit(first, second) }
                )
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(first, second) }, enabled = canSubmit) {
                Text(if (busy) stringResource(R.string.common_authenticating) else stringResource(R.string.common_button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_button_cancel)) }
        }
    )
}

@Composable
private fun PinSingleDialog(
    title: String,
    message: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val canSubmit = !busy && pin.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                PinTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.applock_pin_field_label),
                    enabled = !busy,
                    onDone = { if (canSubmit) { onSubmit(pin); pin = "" } }
                )
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pin); pin = "" }, enabled = canSubmit) {
                Text(if (busy) stringResource(R.string.common_authenticating) else stringResource(R.string.common_button_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_button_cancel)) }
        }
    )
}

@Composable
private fun MessageDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } }
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    message: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selected, role = Role.RadioButton, onClick = { onSelect(index) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == selected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_button_cancel)) } }
    )
}
