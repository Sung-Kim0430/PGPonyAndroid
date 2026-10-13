// FileBatchResultScreen.kt
// PGPony Android 4.7.0 (items 15 and 38, #72)
//
// The sheet for a multi-file operation: each file on its own from Bundle
// mode (encrypt, encrypt and sign, sign only) and the multi-file decrypt.
// One row per file with its status (waiting, working, done, or failed with
// the error text) and, for decrypt, that file's own signature result. The
// passphrase, when one is needed, is asked here once for the whole batch.
// Finished outputs are saved into a folder the user picks
// (ACTION_OPEN_DOCUMENT_TREE) or shared together (ACTION_SEND_MULTIPLE
// through the app's FileProvider, the same way single results are shared).

package com.pgpony.android.ui.encrypt

import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pgpony.android.MainActivity
import com.pgpony.android.R
import com.pgpony.android.ui.decrypt.VerificationBanner
import com.pgpony.android.ui.util.ScratchFiles

private val BatchGreen = Color(0xFF22C55E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBatchResultScreen(
    batch: FileBatchState,
    processedBytes: Long,
    totalBytes: Long,
    onResume: (String) -> Unit,
    onSkipPassphrase: () -> Unit,
    onSaveToFolder: (android.net.Uri) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    // Captured outside the sheet: inside it LocalContext is the dialog
    // window's wrapper, which cannot start the share chooser on its own.
    val context = LocalContext.current
    val activity = context.findBatchMainActivity()
    // A swipe must not close the sheet while files are still being worked
    // on; Cancel is the way out then.
    val running by rememberUpdatedState(batch.running)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !running }
    )
    var passphrase by remember { mutableStateOf("") }
    var shareError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!batch.running) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val total = batch.items.size
            val title = when {
                batch.finished -> stringResource(R.string.batch_title_done_format, batch.doneCount, total)
                batch.kind == FileBatchKind.DECRYPT -> stringResource(R.string.batch_title_decrypting)
                batch.kind == FileBatchKind.SIGN_ONLY -> stringResource(R.string.batch_title_signing)
                else -> stringResource(R.string.batch_title_encrypting)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        batch.kind == FileBatchKind.DECRYPT -> Icons.Filled.LockOpen
                        batch.kind == FileBatchKind.SIGN_ONLY -> Icons.Filled.Draw
                        else -> Icons.Filled.Lock
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            // ── Progress for the file being worked on ───────────────────
            if (batch.running) {
                val current = batch.items.indexOfFirst { it.status is FileBatchStatus.Running }
                if (current >= 0) {
                    Text(
                        stringResource(R.string.batch_progress_file_format, current + 1, total),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_button_cancel))
                }
            }

            // ── Passphrase, asked once for every file ───────────────────
            if (batch.needsPassphrase) {
                Text(
                    stringResource(R.string.batch_passphrase_prompt),
                    style = MaterialTheme.typography.bodyMedium
                )
                batch.passphraseError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.batch_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val entered = passphrase
                            passphrase = ""
                            onResume(entered)
                        },
                        enabled = passphrase.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.batch_passphrase_continue))
                    }
                    OutlinedButton(
                        onClick = {
                            passphrase = ""
                            onSkipPassphrase()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.batch_passphrase_skip))
                    }
                }
            }

            // ── One row per file ────────────────────────────────────────
            // Not a lazy list: the sheet already scrolls, and nesting a
            // vertical scrollable in it throws at runtime.
            batch.items.forEach { item -> BatchItemRow(item = item, decrypt = batch.kind == FileBatchKind.DECRYPT) }

            // ── Save or share the finished outputs ──────────────────────
            if (batch.finished && batch.doneCount > 0) {
                Button(
                    onClick = {
                        activity?.startDocumentTreePicker { uri ->
                            if (uri != null) onSaveToFolder(uri)
                        }
                    },
                    enabled = activity != null && !batch.saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (batch.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.batch_save_to_folder))
                }
                OutlinedButton(
                    onClick = {
                        shareError = null
                        try {
                            shareBatchOutputs(context, batch)
                        } catch (_: Exception) {
                            shareError = context.getString(R.string.batch_share_failed)
                        }
                    },
                    enabled = !batch.saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.batch_share_all))
                }
            }
            batch.saveSummary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shareError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            TextButton(
                onClick = onDismiss,
                enabled = !batch.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_button_done))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BatchItemRow(item: FileBatchItem, decrypt: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    FileBatchStatus.Running ->
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    FileBatchStatus.Done -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.batch_status_done),
                        tint = BatchGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    is FileBatchStatus.Failed -> Icon(
                        Icons.Filled.Error,
                        contentDescription = stringResource(R.string.batch_status_failed),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    FileBatchStatus.Pending -> Icon(
                        Icons.Filled.Schedule,
                        contentDescription = stringResource(R.string.batch_status_pending),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.inputName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    val outName = item.outputName
                    if (item.status is FileBatchStatus.Done && outName != null) {
                        Text(
                            stringResource(R.string.batch_item_output_format, outName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    (item.status as? FileBatchStatus.Failed)?.let { failed ->
                        Text(
                            failed.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    item.savedAs?.let {
                        Text(
                            stringResource(R.string.batch_item_saved_format, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = BatchGreen
                        )
                    }
                    item.saveError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // Decrypt: this file's own signature result.
            val verification = item.verification
            if (decrypt && item.status is FileBatchStatus.Done && verification != null) {
                Spacer(modifier = Modifier.height(8.dp))
                VerificationBanner(result = verification, onTapUnknownSigner = { })
            }
        }
    }
}

/** Every finished output in one ACTION_SEND_MULTIPLE, through the FileProvider. */
private fun shareBatchOutputs(context: Context, batch: FileBatchState) {
    val uris = ArrayList<android.net.Uri>()
    batch.items.forEach { item ->
        val file = item.output
        if (item.status is FileBatchStatus.Done && file != null && file.exists()) {
            uris.add(ScratchFiles.uriFor(context, file))
        }
    }
    if (uris.isEmpty()) throw IllegalStateException("nothing to share")
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        // The grant travels with ClipData on the chooser, so every URI is in it.
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.batch_share_chooser)))
}

/** Same ContextWrapper walk as the other result sheets in this package. */
private tailrec fun Context.findBatchMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findBatchMainActivity()
    else -> null
}
