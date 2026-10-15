// SessionLockReceiver.kt
// PGPony Android — 4.3.0 RC4 §3 (#15) "until the phone locks"
//
// Clears every held secret when the device locks, but ONLY when the user
// chose the "until the phone locks" session option. Registered dynamically
// from PGPonyApp (ACTION_SCREEN_OFF cannot be declared in the manifest on
// modern Android). Screen-off on a device with a secure keyguard is the
// deliberate lock event the plan asks for; a device with no keyguard never
// really "locks", so nothing is cleared there.

package com.pgpony.android.session

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.pgpony.android.crypto.card.CardPinCache
import com.pgpony.android.provider.ProviderPassphraseCache

class SessionLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        if (!SessionPolicy.isUntilLocked()) return
        // Only a secured device actually locks on screen-off.
        val keyguard = context.getSystemService<KeyguardManager>()
        if (keyguard?.isDeviceSecure != true) return
        ProviderPassphraseCache.clearAll()
        com.pgpony.android.provider.RecipientReviewMemory.shared.clear() // 4.7.0 (item 56)
        CardPinCache.clear()
        InAppPassphraseCache.clearAll()
    }
}
