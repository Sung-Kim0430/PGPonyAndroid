// PinLockScreen.kt
// PGPony Android 4.7.0 (item 32): the lock screen for a PGPony PIN.
//
// LockScreen shows this instead of the device-unlock screen in PIN mode, so the
// app, the Quick Action and anything else that shows the lock get it the same
// way. A numeric field with password masking and no keyboard suggestions. The
// fingerprint is offered only when the user kept it on, only as a class 3
// biometric bound to AppLockBiometricKey's CryptoObject, never the device PIN.
// In PIN mode there is no device-credential way in at all: a PIN config that
// cannot be read leaves only a deliberate erase (the same reset as Clear All
// Data), behind a confirmation.
//
// The duress PIN, or a wrong PIN that reaches the wipe limit, starts the wipe
// with nothing on screen to say so: the screen stays in its ordinary checking
// state until the app comes back as a fresh install. Attempts run on the
// process scope, so leaving the screen mid-check neither drops a counted
// attempt nor stops a wipe.

package com.pgpony.android.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.pgpony.android.PGPonyApp
import com.pgpony.android.R
import com.pgpony.android.lock.AppLock
import com.pgpony.android.lock.AppLockBiometricKey
import com.pgpony.android.lock.AppWipe
import com.pgpony.android.lock.PinCheck
import com.pgpony.android.lock.PinFormat
import com.pgpony.android.lock.PinHashParams
import com.pgpony.android.lock.PinRules
import com.pgpony.android.ui.keyring.BiometricAvailability
import com.pgpony.android.ui.keyring.BiometricGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 4.7.0 (item 32): a PIN field. Digits only, at most [PinRules.MAX_LENGTH],
 * masked, on the number-password keyboard (no suggestions, no learning).
 */
@Composable
fun PinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it in '0'..'9' }.take(PinRules.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth()
    )
}

/** The outcome of a PIN attempt, as the screens that ask for a PIN show it. */
internal sealed class PinAttemptUi {
    data object Unlocked : PinAttemptUi()
    data object Wrong : PinAttemptUi()
    data class Wait(val remainingMs: Long) : PinAttemptUi()
    /** No readable PIN config: only an erase gets out. */
    data object Unavailable : PinAttemptUi()
    /** The check itself failed (a full disk, out of memory): try again. Never an unlock. */
    data object Failed : PinAttemptUi()
}

/**
 * 4.7.0 (item 32): run one attempt on the process scope. The duress PIN and the
 * wipe limit go straight to the silent wipe and never call [onResult]; every
 * other outcome comes back on the main thread. Shared by the lock screen and
 * the PIN check in Settings, so the duress PIN behaves the same in each.
 */
internal fun submitPinAttempt(
    context: android.content.Context,
    activity: android.app.Activity?,
    pin: String,
    onResult: (PinAttemptUi) -> Unit
) {
    val app = context.applicationContext
    PGPonyApp.instance.applicationScope.launch {
        val outcome: PinCheck? = try {
            AppLock.submit(app, pin)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        val ui: PinAttemptUi? = when (outcome) {
            null -> PinAttemptUi.Failed
            PinCheck.Unlocked -> PinAttemptUi.Unlocked
            PinCheck.Duress, is PinCheck.WipeLimit -> null
            is PinCheck.Wrong ->
                if (outcome.delayMs > 0L) PinAttemptUi.Wait(outcome.delayMs) else PinAttemptUi.Wrong
            is PinCheck.Throttled -> PinAttemptUi.Wait(outcome.remainingMs)
            PinCheck.Unsupported -> PinAttemptUi.Unavailable
        }
        if (ui == null) {
            AppWipe.wipeAndRestart(app, activity)
        } else {
            withContext(Dispatchers.Main) { onResult(ui) }
        }
    }
}

@Composable
fun PinLockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c != null && c !is FragmentActivity) {
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        c as? FragmentActivity
    }

    var pin by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var fingerprintAllowed by remember { mutableStateOf(false) }
    // The countdown on screen runs on time since boot, like the delay itself.
    var waitUntil by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val focus = remember { FocusRequester() }

    // Fingerprint unlock whose key is gone or invalidated is turned off, and
    // the screen says why (this once: afterwards the option is simply off).
    val dropFingerprint: () -> Unit = {
        fingerprintAllowed = false
        biometricError = context.getString(R.string.applock_fingerprint_reset_notice)
        val app = context.applicationContext
        PGPonyApp.instance.applicationScope.launch { AppLock.setFingerprintAllowed(app, false) }
    }

    LaunchedEffect(Unit) {
        val config = AppLock.load(context)
        if (config == null || PinHashParams.forVersion(config.version) == null) {
            unavailable = true
        } else {
            if (config.fingerprintAllowed) {
                val keyOk = withContext(Dispatchers.IO) { AppLockBiometricKey.cipherForUnlock() != null }
                if (keyOk) fingerprintAllowed = true else dropFingerprint()
            }
            val remaining = AppLock.throttleRemainingMs(context, config)
            if (remaining > 0L) waitUntil = SystemClock.elapsedRealtime() + remaining
        }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(waitUntil) {
        nowMs = SystemClock.elapsedRealtime()
        while (nowMs < waitUntil) {
            delay(500L)
            nowMs = SystemClock.elapsedRealtime()
        }
    }
    val remainingSec = ((waitUntil - nowMs).coerceAtLeast(0L) + 999L) / 1000L
    val waiting = remainingSec > 0L
    val canSubmit = !checking && !waiting && PinRules.check(pin) == PinFormat.OK

    val submit: () -> Unit = {
        if (canSubmit) {
            val entered = pin
            checking = true
            wrong = false
            failed = false
            biometricError = null
            submitPinAttempt(context, activity, entered) { result ->
                pin = ""
                checking = false
                when (result) {
                    PinAttemptUi.Unlocked -> onUnlock()
                    PinAttemptUi.Wrong -> wrong = true
                    is PinAttemptUi.Wait -> {
                        wrong = true
                        waitUntil = SystemClock.elapsedRealtime() + result.remainingMs
                    }
                    PinAttemptUi.Unavailable -> unavailable = true
                    PinAttemptUi.Failed -> failed = true
                }
            }
        }
    }

    val biometricAvailable = remember(activity, fingerprintAllowed) {
        fingerprintAllowed && activity != null &&
            BiometricGate.canAuthenticateBiometricOnly(activity) == BiometricAvailability.Available
    }

    // 4.7.0 (item 32): the keyboard covered the Unlock and fingerprint
    // buttons. The screen now sits above the keyboard (imePadding, with the
    // app drawing edge to edge) and, while the keyboard is up, the lock icon
    // shrinks and the tagline goes so the buttons stay in view.
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (imeVisible) 72.dp else 120.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(32.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (imeVisible) 40.dp else 64.dp)
                )
            }
            Spacer(modifier = Modifier.height(if (imeVisible) 16.dp else 32.dp))
            Text(
                stringResource(R.string.app_name),
                fontSize = if (imeVisible) 24.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!imeVisible) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.lock_screen_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(if (imeVisible) 16.dp else 32.dp))

            if (unavailable) {
                // Locked and unreadable: no device credential, no way round the
                // PIN. Only a deliberate erase.
                Text(
                    stringResource(R.string.applock_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmErase = true },
                    enabled = !erasing,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.applock_reset_button), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                PinTextField(
                    value = pin,
                    onValueChange = { pin = it; wrong = false; failed = false },
                    label = stringResource(R.string.applock_pin_field_label),
                    enabled = !checking && !waiting,
                    modifier = Modifier.focusRequester(focus),
                    onDone = submit
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(imageVector = Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (checking) stringResource(R.string.common_authenticating) else stringResource(R.string.lock_screen_unlock_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (biometricAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            biometricError = null
                            activity?.let { a ->
                                val cipher = AppLockBiometricKey.cipherForUnlock()
                                if (cipher == null) {
                                    dropFingerprint()
                                } else {
                                    runCatching {
                                        BiometricGate.authenticateBiometricWithCrypto(
                                            activity = a,
                                            title = a.getString(R.string.lock_prompt_title),
                                            subtitle = a.getString(R.string.lock_prompt_subtitle),
                                            cipher = cipher,
                                            onSuccess = { unlocked ->
                                                // Only a cipher the enrolled fingerprint really
                                                // unlocked counts.
                                                if (AppLockBiometricKey.proves(unlocked)) {
                                                    val app = a.applicationContext
                                                    PGPonyApp.instance.applicationScope.launch { AppLock.onBiometricUnlock(app) }
                                                    onUnlock()
                                                } else {
                                                    dropFingerprint()
                                                }
                                            },
                                            onError = { _, message -> biometricError = message }
                                        )
                                    }
                                }
                            }
                        },
                        enabled = !checking
                    ) {
                        Text(stringResource(R.string.applock_use_fingerprint))
                    }
                }
                val message: String? = when {
                    waiting -> stringResource(R.string.applock_wait_format, remainingSec.toInt())
                    failed -> stringResource(R.string.applock_check_failed)
                    wrong -> stringResource(R.string.applock_wrong_pin)
                    else -> null
                }
                message?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
            biometricError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { if (!erasing) confirmErase = false },
            title = { Text(stringResource(R.string.applock_reset_confirm_title)) },
            text = { Text(stringResource(R.string.applock_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        erasing = true
                        AppWipe.wipeAndRestart(context, activity)
                    },
                    enabled = !erasing
                ) {
                    Text(
                        stringResource(R.string.applock_reset_confirm_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }, enabled = !erasing) {
                    Text(stringResource(R.string.common_button_cancel))
                }
            }
        )
    }
}
