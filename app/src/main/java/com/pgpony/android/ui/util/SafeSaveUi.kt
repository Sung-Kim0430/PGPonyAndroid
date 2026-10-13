// SafeSaveUi.kt
// PGPony Android 4.7.0 (item 37, #73): the "Saving" row every result sheet
// shows while a SafeSave copy runs, and the hooks a sheet uses to follow it.

package com.pgpony.android.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pgpony.android.R

/**
 * While this is in the composition, saves under [key] count as watched, so
 * one that ends shows its result here instead of a Toast.
 */
@Composable
fun ObserveSafeSave(key: String) {
    DisposableEffect(key) {
        SafeSave.attach(key)
        onDispose { SafeSave.detach(key) }
    }
}

/** [task]'s current state, or null with no task. */
@Composable
fun rememberSaveState(task: SafeSave.Task?): SafeSave.State? {
    if (task == null) return null
    return task.state.collectAsState().value
}

/**
 * "Saving" with a bar: determinate (bytes written of the total) when the
 * size is known, indeterminate otherwise, and a Cancel. Shows nothing unless
 * [state] is running.
 */
@Composable
fun SafeSaveProgress(
    state: SafeSave.State?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val running = state as? SafeSave.State.Running ?: return
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val label = if (running.total > 0L) {
                stringResource(
                    R.string.save_progress_bytes_format,
                    android.text.format.Formatter.formatShortFileSize(
                        context, running.written.coerceAtMost(running.total)
                    ),
                    android.text.format.Formatter.formatShortFileSize(context, running.total)
                )
            } else {
                stringResource(R.string.save_progress_saving)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_button_cancel))
                }
            }
        }
        if (running.total > 0L) {
            LinearProgressIndicator(
                progress = { (running.written.toFloat() / running.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
