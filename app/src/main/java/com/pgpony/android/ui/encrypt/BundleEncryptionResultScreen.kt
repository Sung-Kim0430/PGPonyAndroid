// BundleEncryptionResultScreen.kt
// PGPony Android — 3.1.0 Phase 5 (J4)
//
// The output sheet for an encrypted Bundle. Three formats, mirroring
// iOS MessageEncryptionResultView:
//
//   • Share as Email (.eml) — the armored message wrapped in the RFC
//     3156 multipart/encrypted envelope (MimeBuilder.wrapEncrypted),
//     written to the cache exports dir and shared as message/rfc822.
//     The most interoperable: Thunderbird and desktop clients open it
//     directly, and PGPony's own J2 unwrap reads it back.
//   • Share encrypted .asc — the armored block as a standalone file.
//   • Copy Inline Block — the armored block to the clipboard via
//     ClipboardService (auto-clear countdown per the app convention).
//
// FileProvider one-shot grants per the A7 Fix3 pattern; octet-stream
// is NOT needed here because these are share intents, not SAF creates
// (the Phase 2 Fix1 extension-append issue is a document-creator
// behavior).

package com.pgpony.android.ui.encrypt

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pgpony.android.PGPonyApp
import com.pgpony.android.autocrypt.AutocryptHeader
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pgpony.android.MainActivity
import com.pgpony.android.R
import com.pgpony.android.crypto.mime.MimeBuilder
import com.pgpony.android.ui.util.ClipboardService
import com.pgpony.android.ui.util.ObserveSafeSave
import com.pgpony.android.ui.util.SafeSave
import com.pgpony.android.ui.util.SafeSaveProgress
import com.pgpony.android.ui.util.rememberSaveState
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleEncryptionResultScreen(state: EncryptUiState, onDismiss: () -> Unit) {
    // 4.0.0 Phase 4 — our own Autocrypt header, injected into the .eml
    // output (best-effort; see MimeBuilder.wrapEncrypted).
    var autocryptHeader by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        autocryptHeader = runCatching {
            AutocryptHeader.currentUserHeader(PGPonyApp.instance.keyRepository)
        }.getOrNull()
    }
    val context = LocalContext.current
    // 3.1.0 Phase 5 Fix1: Save-to-Files needs the SAF document creator,
    // which lives on MainActivity (A10b helper).
    val activity = context.findBundleResultMainActivity()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 4.2.0 RC6 (#32, tail): the result is either a String (small) or a
    // scratch file (large — a 750 MB bundle armors to ~1.4 GB and can
    // never be a String). File-backed results stream their shares and
    // saves; only Copy Inline needs the String and is disabled otherwise.
    val armored = state.encryptedBundleArmored
    val armoredFile = state.encryptedBundleFile
    if (armored == null && armoredFile == null) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 4.2.0 RC6 (#32, tail-2): a file-backed export writes ~1.4 GB in
    // the background, which takes real time. Without visible state the
    // sheet looked done while the SAF file was still empty, and picking
    // that file in Decrypt produced "invalid header" on 0 bytes (the
    // exact repro from Kevin's device pass). While an export runs the
    // action rows and Done are disabled and a writing note shows; on
    // completion the note flips to the green Saved. convention.
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    val writeAsc: (java.io.OutputStream) -> Unit = { out ->
        if (armored != null) out.write(armored.toByteArray(Charsets.UTF_8))
        else java.io.FileInputStream(armoredFile!!).use { it.copyTo(out) }
    }
    // §5.6.3 (#31): wrap the .asc ciphertext in a zip for transport. The .eml
    // envelope is left as-is (mail clients expect .eml).
    val prefs = remember { context.getSharedPreferences("pgpony_prefs", android.content.Context.MODE_PRIVATE) }
    var wrapZip by remember { mutableStateOf(prefs.getBoolean("wrap_output_in_zip", false)) }
    val ascName = if (wrapZip) "message.asc.zip" else "message.asc"
    val ascMime = if (wrapZip) "application/zip" else "application/pgp-encrypted"
    val writeAscOut: (java.io.OutputStream) -> Unit = { out ->
        if (wrapZip) com.pgpony.android.ui.util.ZipPackaging.writeSingleEntryNoClose(out, "message.asc", writeAsc)
        else writeAsc(out)
    }
    val exportCtl = ExportControl(
        begin = { exporting = true; exportNote = null },
        finish = { ok ->
            exporting = false
            exportNote = context.getString(
                if (ok) R.string.result_save_saved_note else R.string.result_save_failed_note
            )
        }
    )
    // 4.7.0 (item 37, #73): Save runs in SafeSave on the process scope with
    // progress, so it keeps going when the sheet closes, and a failed or
    // cancelled save deletes the half-written document. Shares still use
    // ExportControl and still hold the sheet open while they write.
    var saveTask by remember { mutableStateOf<SafeSave.Task?>(SafeSave.running(SafeSave.KEY_BUNDLE)) }
    val saveState: SafeSave.State? = rememberSaveState(saveTask)
    val saving = saveState is SafeSave.State.Running
    ObserveSafeSave(SafeSave.KEY_BUNDLE)
    val savedNoteText = stringResource(R.string.result_save_saved_note)
    val failedNoteText = stringResource(R.string.result_save_failed_note)
    val cancelledNoteText = stringResource(R.string.save_cancelled)
    LaunchedEffect(saveState) {
        when (saveState) {
            is SafeSave.State.Done -> exportNote = savedNoteText
            is SafeSave.State.Failed -> exportNote = failedNoteText
            is SafeSave.State.Cancelled -> exportNote = cancelledNoteText
            is SafeSave.State.Running, null -> { }
        }
    }
    val startSave: (String, Long, (java.io.OutputStream) -> Unit) -> Unit = { name, minBytes, write ->
        activity?.startDocumentCreator(
            mimeType = "application/octet-stream",
            suggestedName = name
        ) { uri ->
            if (uri != null) {
                exportNote = null
                saveTask = SafeSave.saveToDocument(
                    context, SafeSave.KEY_BUNDLE, uri, name,
                    totalBytes = armoredFile?.length() ?: (armored?.length?.toLong() ?: 0L),
                    minExpectedBytes = minBytes,
                    write = write
                )
            }
        }
    }
    val recipients = state.selectedRecipients.size
    val attachmentCount = state.bundleAttachments.size

    // Swipe-down is also blocked mid-export — dismissal deletes the
    // scratch file the write streams from.
    ModalBottomSheet(
        onDismissRequest = { if (!exporting) onDismiss() },
        sheetState = sheetState
    ) {
        // 4.1.1: issue #23's class, caught by the same audit. Four stacked
        // action rows fit a reference device with room to spare, and a large
        // font scale spends that room; without a scroll container the Done
        // row is the first thing to leave the screen. Scroll goes first in
        // the chain; the existing padding and inset order is unchanged.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                stringResource(R.string.bundle_result_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.bundle_result_subtitle_format, attachmentCount, recipients),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            // §5.6.3 (#31, Araaf RC3 retest): split into an email block and a
            // file block so the Wrap-in-.zip toggle plainly scopes the .asc
            // file, not the .eml envelope.
            SectionLabel(stringResource(R.string.bundle_result_section_email))
            // 3.1.0 Phase 5 Fix1 — each format gets Share AND Save. Save
            // goes through the SAF document creator with octet-stream (the
            // Phase 2 Fix1 lesson: typed MIMEs get their canonical
            // extension appended, which would produce message.eml.eml).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = !exporting,
                    onClick = {
                        shareBundleStream(context, scope, exportCtl, "message.eml", "message/rfc822") { out ->
                            if (armored != null) {
                                out.write(MimeBuilder.wrapEncrypted(armored, autocryptHeader = autocryptHeader))
                            } else {
                                MimeBuilder.wrapEncryptedTo(
                                    out,
                                    { java.io.FileInputStream(armoredFile!!) },
                                    autocryptHeader = autocryptHeader
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.bundle_result_share_eml))
                }
                OutlinedButton(
                    enabled = !exporting && !saving && activity != null,
                    onClick = {
                        startSave("message.eml", armoredFile?.length() ?: 0L) { out ->
                            if (armored != null) {
                                out.write(MimeBuilder.wrapEncrypted(armored, autocryptHeader = autocryptHeader))
                            } else {
                                // 4.7.0 (item 37): opened before the first
                                // byte, so closing the sheet (which deletes
                                // the scratch file) cannot cut the copy short.
                                java.io.FileInputStream(armoredFile!!).use { src ->
                                    MimeBuilder.wrapEncryptedTo(
                                        out,
                                        { src },
                                        autocryptHeader = autocryptHeader
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = stringResource(R.string.bundle_result_save_eml)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.bundle_result_section_file))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Switch(
                    checked = wrapZip,
                    onCheckedChange = {
                        wrapZip = it
                        prefs.edit().putBoolean("wrap_output_in_zip", it).apply()
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.enc_result_wrap_zip_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.enc_result_wrap_zip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = !exporting,
                    onClick = {
                        shareBundleStream(context, scope, exportCtl, ascName, ascMime, writeAscOut)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.bundle_result_share_asc))
                }
                OutlinedButton(
                    enabled = !exporting && !saving && activity != null,
                    onClick = {
                        startSave(
                            ascName,
                            if (wrapZip) 0L else (armoredFile?.length() ?: 0L),
                            writeAscOut
                        )
                    }
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = stringResource(R.string.bundle_result_save_asc)
                    )
                }
            }
            OutlinedButton(
                onClick = { armored?.let { ClipboardService.copyText(context, it) } },
                enabled = armored != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.bundle_result_copy_inline))
            }
            if (armored == null) {
                Text(
                    stringResource(R.string.bundle_result_copy_too_large),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 4.7.0 (item 37): "Saving" with bytes written while a save runs.
            SafeSaveProgress(state = saveState, onCancel = { saveTask?.cancel() })
            if (exporting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.bundle_result_export_writing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                exportNote?.let {
                    val failed = it == failedNoteText || it == cancelledNoteText
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error
                                else androidx.compose.ui.graphics.Color(0xFF22C55E)
                    )
                }
            }
            // Done stays disabled during an export: dismissing deletes
            // the scratch source the write is streaming from.
            OutlinedButton(
                onClick = onDismiss,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_button_done))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// 4.7.0 (item 37, #73): saveBundleStream (the screen-scoped SAF save with
// its byte counter) became startSave above, through SafeSave, which keeps
// the octet-stream picker (3.1.0 Phase 5 Fix1), the off-main-thread write
// and the short-write check (4.2.0 RC6, #32), and adds progress, cancel and
// delete-on-failure.
/** 4.2.0 RC6 (#32, tail-2): begin/finish hooks the sheet uses to show
 *  export progress and gate its buttons. finish(true) = complete. Both
 *  are invoked on Main. */
private class ExportControl(
    val begin: () -> Unit,
    val finish: (ok: Boolean) -> Unit
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
}

private tailrec fun Context.findBundleResultMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findBundleResultMainActivity()
    else -> null
}

// 4.2.0 RC6 (#32, tail): writer-based and off the main thread, same
// reasoning as saveBundleStream; the chooser fires on Main once the
// export copy exists.
private fun shareBundleStream(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    ctl: ExportControl,
    name: String,
    mime: String,
    write: (java.io.OutputStream) -> Unit
) {
    ctl.begin()
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val ok = try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = com.pgpony.android.ui.util.ScratchFiles.safeChild(exportsDir, name, "bundle.pgp")
            outFile.outputStream().buffered().use(write)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, null))
            }
            true
        } catch (t: Throwable) {
            // Share sheet unavailable or the export copy failed —
            // non-fatal; the other outputs remain. Logged for diagnosis.
            android.util.Log.e("PGPonyBundleExport", "share $name failed", t)
            false
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            ctl.finish(ok)
        }
    }
}
