// ProviderAutoFocus.kt
// PGPony Android, 4.7.0 (item 50, #59 reopened)
//
// 4.5.0 (item 20) focused the passphrase field and raised the keyboard in the
// in-app unlock dialogs, but not in the prompts a mail app opens through the
// OpenPGP API (Unlock decryption key, Unlock signing key, the card PIN), which
// is where the report came from. Those dialogs run in their own activity in
// :remote_api, where the focus alone does not bring the keyboard up, so this
// asks the keyboard controller too.

package com.pgpony.android.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Attach to the field with Modifier.focusRequester; it takes focus and the keyboard opens. */
@Composable
internal fun rememberProviderAutoFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // The dialog window attaches a moment after the first composition.
        kotlinx.coroutines.delay(100)
        runCatching { requester.requestFocus() }
        keyboard?.show()
    }
    return requester
}
