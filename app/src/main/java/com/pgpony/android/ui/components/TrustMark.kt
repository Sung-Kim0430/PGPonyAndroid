// TrustMark.kt
// PGPony Android — 4.3.0 RC4 #24 (trust icon swap), reworked in 4.7.0 (item 10, #75)
//
// Single source of truth for the trust ladder. KeyCard, KeyDetail, the
// Contacts badge and the recipient picker all render through TrustMark so
// the surfaces can never drift. 4.7.0 (#75): a traffic light for public
// keys, with Ultimate set apart by shape and Don't use as its own sign:
//
//   UNKNOWN    shield + X      grey        (GppBad)
//   UNVERIFIED shield + "!"    amber       (GppMaybe)
//   VERIFIED   shield + check  green       (GppGood)
//   ULTIMATE   star on shield  blue-violet (white Star over a filled Shield)
//   DONT_USE   no-entry sign   red         (DoNotDisturbOn)
//
// This undoes part of #36, where green moved to the top rung and Verified
// went blue: green means good to go again, and Ultimate keeps its own shape.

package com.pgpony.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pgpony.android.data.TrustLevel

/** The trust palette, defined once (4.7.0, #75). */
object TrustColors {
    val Unknown = Color(0xFF9CA3AF)    // grey-400
    val Unverified = Color(0xFFF59E0B) // amber-500
    val Verified = Color(0xFF22C55E)   // green-500
    val Ultimate = Color(0xFF6366F1)   // indigo-500, blue-violet
    val DontUse = Color(0xFFEF4444)    // red-500
}

fun trustColor(trust: TrustLevel): Color = when (trust) {
    TrustLevel.UNKNOWN -> TrustColors.Unknown
    TrustLevel.UNVERIFIED -> TrustColors.Unverified
    TrustLevel.VERIFIED -> TrustColors.Verified
    TrustLevel.ULTIMATE -> TrustColors.Ultimate
    TrustLevel.DONT_USE -> TrustColors.DontUse
}

/**
 * Render the trust mark for [trust] at [size]. Unknown, Unverified and
 * Verified are single shield glyphs, Don't use is the no-entry sign; Ultimate
 * is a white star over a filled blue-violet shield, nudged up a hair for the
 * shield's optical center.
 */
@Composable
fun TrustMark(
    trust: TrustLevel,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = trust.localizedName()
) {
    val tint = trustColor(trust)
    val shield: ImageVector? = when (trust) {
        TrustLevel.UNKNOWN -> Icons.Filled.GppBad
        TrustLevel.UNVERIFIED -> Icons.Filled.GppMaybe
        TrustLevel.VERIFIED -> Icons.Filled.GppGood
        TrustLevel.ULTIMATE -> null
        TrustLevel.DONT_USE -> Icons.Filled.DoNotDisturbOn
    }
    if (shield != null) {
        Icon(
            imageVector = shield,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size)
        )
    } else {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size)
            )
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(size * 0.46f)
                    .offset(y = -(size * 0.045f))
            )
        }
    }
}
