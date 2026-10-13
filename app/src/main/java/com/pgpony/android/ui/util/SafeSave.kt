// SafeSave.kt
// PGPony Android 4.7.0 (item 37, #73): saves that show progress, outlive the
// screen, and never leave a cut-short file that looks complete.
//
// Every Save used to copy from scratch (or memory) to the chosen location in a
// coroutine owned by the result sheet, with at most a spinner. A 2 GB decrypt
// takes long enough to save that people leave the app, and a process killed
// in that window left a partial file under the final name.
//
// Now the copy runs here, on PGPonyApp.applicationScope (Dispatchers.IO, a
// SupervisorJob, alive as long as the process), through SafeSaveCore. The
// sheet reads progress from the Task it gets back and can cancel it. A save
// that ends while no screen is watching its key says so with a Toast.
//
// Two kinds of destination:
//
//   - A document the user created through ACTION_CREATE_DOCUMENT. The name
//     is the user's choice and the provider owns it; renaming a document is
//     optional for providers (FLAG_SUPPORTS_RENAME) and can change the name
//     the user picked, so it cannot be done safely across providers. These
//     saves write straight to that document and delete it on failure or
//     cancel. A force-stop in the middle can still leave a partial file
//     there; nothing in-process can run after a force-stop.
//   - A folder (ACTION_OPEN_DOCUMENT_TREE, the batch Save to a folder). Each
//     file is written as ".<name>.part" and renamed to its final name only
//     after the last byte and the close, so an interrupted save leaves only a
//     hidden .part file. A provider that cannot rename gets the direct write
//     with delete-on-failure instead.
//
// On sources: a result sheet that closes clears its scratch scope, which
// unlinks the file being copied. Each source stream is opened before
// anything is written, and an open stream keeps reading an unlinked file on
// Android's file systems, so closing the sheet mid-save does not cut the copy
// short.

package com.pgpony.android.ui.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.pgpony.android.PGPonyApp
import com.pgpony.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SafeSave {

    private const val TAG = "PGPonySafeSave"
    private const val MIME = "application/octet-stream"

    // One key per surface. A screen watches its key (ObserveSafeSave) and
    // picks up a save still running for it (running) when it opens again.
    const val KEY_DECRYPT = "decrypt"
    const val KEY_ENCRYPT = "encrypt"
    const val KEY_BUNDLE = "bundle"
    const val KEY_BATCH = "batch"
    const val KEY_QUICK = "quick"

    sealed class State {
        /** [total] is 0 or less when the size is not known. */
        data class Running(val written: Long, val total: Long) : State()
        data class Done(val written: Long) : State()
        data object Cancelled : State()
        data class Failed(val message: String) : State()
    }

    class Task internal constructor(val key: String, val displayName: String, total: Long) {
        private val cancelFlag = AtomicBoolean(false)
        private val _state = MutableStateFlow<State>(State.Running(0L, total))
        val state: StateFlow<State> = _state.asStateFlow()
        val isCancelled: Boolean get() = cancelFlag.get()
        val isRunning: Boolean get() = _state.value is State.Running

        /** Stop at the next chunk; the partial output is removed. */
        fun cancel() {
            cancelFlag.set(true)
        }

        internal fun update(s: State) {
            _state.value = s
        }
    }

    /** A file for [saveFilesToTree] and the name it should end up with. */
    class TreeItem(val source: File, val desiredName: String)

    /** One [TreeItem]'s result: the name it got, or why it was not saved. */
    class TreeItemResult(val savedAs: String?, val error: String?)

    private val active = ConcurrentHashMap<String, Task>()
    private val observers = ConcurrentHashMap<String, AtomicInteger>()

    /** The save still running for [key], if any. */
    fun running(key: String): Task? = active[key]?.takeIf { it.isRunning }

    /** A screen showing [key]'s saves is on screen. Pair with [detach]. */
    fun attach(key: String) {
        observers.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
    }

    fun detach(key: String) {
        observers[key]?.decrementAndGet()
    }

    private fun observed(key: String): Boolean = (observers[key]?.get() ?: 0) > 0

    // ── Saves to a document the user created ─────────────────────────

    /** Copy [source] to [target]. The size is read from the open stream. */
    fun saveFileToDocument(context: Context, key: String, target: Uri, displayName: String, source: File): Task {
        val app = context.applicationContext
        val task = register(key, displayName, 0L)
        PGPonyApp.instance.applicationScope.launch {
            val outcome = SafeSaveCore.runCopy(
                openInput = {
                    val fis = FileInputStream(source)
                    val size = runCatching { fis.channel.size() }.getOrDefault(0L)
                    task.update(State.Running(0L, size))
                    fis
                },
                openSink = { openSink(app, target) },
                isCancelled = { task.isCancelled },
                onProgress = { n -> task.update(State.Running(n, totalOf(task))) },
                cleanup = { deleteDocument(app, target) },
            )
            finishDocument(app, task, target, outcome)
        }
        return task
    }

    /** Copy in-memory [bytes] to [target]. */
    fun saveBytesToDocument(context: Context, key: String, target: Uri, displayName: String, bytes: ByteArray): Task {
        val app = context.applicationContext
        val total = bytes.size.toLong()
        val task = register(key, displayName, total)
        PGPonyApp.instance.applicationScope.launch {
            val outcome = SafeSaveCore.runCopy(
                openInput = { ByteArrayInputStream(bytes) },
                openSink = { openSink(app, target) },
                isCancelled = { task.isCancelled },
                onProgress = { n -> task.update(State.Running(n, total)) },
                cleanup = { deleteDocument(app, target) },
            )
            finishDocument(app, task, target, outcome)
        }
        return task
    }

    /**
     * Hand [target]'s stream to [write] (a zip wrapper, a MIME envelope).
     * [totalBytes] drives the progress bar (0 when unknown); fewer than
     * [minExpectedBytes] written counts as a failure.
     */
    fun saveToDocument(
        context: Context,
        key: String,
        target: Uri,
        displayName: String,
        totalBytes: Long,
        minExpectedBytes: Long = 0L,
        write: (OutputStream) -> Unit,
    ): Task {
        val app = context.applicationContext
        val task = register(key, displayName, totalBytes)
        PGPonyApp.instance.applicationScope.launch {
            val outcome = SafeSaveCore.run(
                openSink = { openSink(app, target) },
                write = write,
                isCancelled = { task.isCancelled },
                onProgress = { n -> task.update(State.Running(n, totalBytes)) },
                cleanup = { deleteDocument(app, target) },
                minExpectedBytes = minExpectedBytes,
            )
            finishDocument(app, task, target, outcome)
        }
        return task
    }

    // ── Saves into a folder ──────────────────────────────────────────

    /**
     * 4.7.0 (items 15, 37, 38): copy each of [items] into the folder at
     * [treeUri], never over a file already there ([uniqueName] picks the
     * name against what is taken), through a ".part" name renamed at the
     * end. [onItem] gets each result by its index in [items]; [onFinished]
     * gets them all, or null when the folder cannot be used. Both run on
     * the main thread.
     */
    fun saveFilesToTree(
        context: Context,
        key: String,
        treeUri: Uri,
        items: List<TreeItem>,
        uniqueName: (String, Set<String>) -> String,
        onItem: (Int, TreeItemResult) -> Unit,
        onFinished: (List<TreeItemResult>?) -> Unit,
    ): Task {
        val app = context.applicationContext
        val task = register(key, app.getString(R.string.save_progress_saving), 0L)
        PGPonyApp.instance.applicationScope.launch {
            val dir: DocumentFile? = try {
                DocumentFile.fromTreeUri(app, treeUri)
            } catch (_: Exception) {
                null
            }
            if (dir == null || !dir.isDirectory) {
                val message = app.getString(R.string.batch_save_folder_unusable)
                active.remove(task.key, task)
                task.update(State.Failed(message))
                withContext(Dispatchers.Main) {
                    onFinished(null)
                    if (!observed(task.key)) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            // Every source is opened before the first write (see the header).
            val inputs: List<FileInputStream?> = items.map { item ->
                try { FileInputStream(item.source) } catch (_: Exception) { null }
            }
            val sizes: List<Long> = inputs.map { s ->
                if (s == null) 0L else runCatching { s.channel.size() }.getOrDefault(0L)
            }
            val total = sizes.sum()
            task.update(State.Running(0L, total))
            val taken = HashSet<String>()
            try { dir.listFiles().forEach { f -> f.name?.let { taken.add(it) } } } catch (_: Exception) {}
            val results = ArrayList<TreeItemResult>(items.size)
            var base = 0L
            try {
                for ((i, item) in items.withIndex()) {
                    val input = inputs[i]
                    val result: TreeItemResult = when {
                        task.isCancelled ->
                            TreeItemResult(null, app.getString(R.string.save_batch_item_cancelled))
                        input == null ->
                            TreeItemResult(null, app.getString(R.string.batch_save_failed_item))
                        else -> saveOneToTree(app, dir, input, item.desiredName, taken, uniqueName, task) { n ->
                            task.update(State.Running(base + n, total))
                        }
                    }
                    results.add(result)
                    base += sizes[i]
                    withContext(Dispatchers.Main) { onItem(i, result) }
                }
            } finally {
                inputs.forEach { s -> runCatching { s?.close() } }
            }
            val savedCount = results.count { it.savedAs != null }
            active.remove(task.key, task)
            task.update(if (task.isCancelled) State.Cancelled else State.Done(base))
            withContext(Dispatchers.Main) {
                onFinished(results)
                if (!observed(task.key) && !task.isCancelled) {
                    Toast.makeText(
                        app,
                        app.getString(R.string.batch_saved_summary_format, savedCount, items.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return task
    }

    private fun saveOneToTree(
        app: Context,
        dir: DocumentFile,
        input: InputStream,
        desiredName: String,
        taken: MutableSet<String>,
        uniqueName: (String, Set<String>) -> String,
        task: Task,
        onProgress: (Long) -> Unit,
    ): TreeItemResult {
        val finalName = uniqueName(desiredName, taken)
        var doc: DocumentFile? = null
        var needsRename = false
        var actual: String? = null
        val outcome = SafeSaveCore.runCopy(
            openInput = { input },
            openSink = {
                val part = dir.createFile(MIME, SafeSaveCore.partName(finalName))
                val d: DocumentFile = if (part != null && supportsRename(app, part.uri)) {
                    needsRename = true
                    part
                } else {
                    // No rename here (or no hidden names): write under the
                    // final name and rely on delete-on-failure.
                    if (part != null) runCatching { part.delete() }
                    dir.createFile(MIME, finalName) ?: throw IOException("createFile failed")
                }
                doc = d
                app.contentResolver.openOutputStream(d.uri) ?: throw IOException("No output stream")
            },
            isCancelled = { task.isCancelled },
            onProgress = onProgress,
            cleanup = { runCatching { doc?.delete() } },
            commit = {
                val d = doc ?: throw IOException("No document")
                if (needsRename && !d.renameTo(finalName)) throw IOException("Rename failed")
                actual = d.name ?: finalName
            },
        )
        return when (outcome) {
            is SafeSaveCore.Outcome.Done -> {
                val name = actual ?: finalName
                taken.add(finalName)
                taken.add(name)
                TreeItemResult(name, null)
            }
            is SafeSaveCore.Outcome.Cancelled ->
                TreeItemResult(null, app.getString(R.string.save_batch_item_cancelled))
            is SafeSaveCore.Outcome.Failed -> {
                Log.e(TAG, "save to folder failed", outcome.cause)
                TreeItemResult(
                    null,
                    if (isNoSpace(outcome.cause)) app.getString(R.string.save_failed_no_space)
                    else app.getString(R.string.batch_save_failed_item)
                )
            }
        }
    }

    // ── Shared plumbing ──────────────────────────────────────────────

    private fun register(key: String, displayName: String, total: Long): Task {
        val task = Task(key, displayName, total)
        active[key] = task
        return task
    }

    private fun totalOf(task: Task): Long =
        (task.state.value as? State.Running)?.total ?: 0L

    private fun openSink(app: Context, target: Uri): OutputStream =
        app.contentResolver.openOutputStream(target) ?: throw IOException("No output stream")

    /** Remove a partial document. Best effort: a provider may refuse. */
    private fun deleteDocument(app: Context, target: Uri) {
        val gone = try {
            DocumentsContract.deleteDocument(app.contentResolver, target)
        } catch (_: Exception) {
            false
        }
        if (!gone) runCatching { DocumentFile.fromSingleUri(app, target)?.delete() }
    }

    private fun supportsRename(app: Context, uri: Uri): Boolean = try {
        app.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
            else false
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun isNoSpace(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            val m = c.message
            if (m != null && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true))) return true
            c = c.cause
        }
        return false
    }

    private suspend fun finishDocument(app: Context, task: Task, target: Uri, outcome: SafeSaveCore.Outcome) {
        val state: State = when (outcome) {
            is SafeSaveCore.Outcome.Done -> State.Done(outcome.bytes)
            is SafeSaveCore.Outcome.Cancelled -> State.Cancelled
            is SafeSaveCore.Outcome.Failed -> {
                Log.e(TAG, "save ${task.key} failed", outcome.cause)
                State.Failed(
                    app.getString(
                        if (isNoSpace(outcome.cause)) R.string.save_failed_no_space
                        else R.string.save_failed_generic
                    )
                )
            }
        }
        // The name the user typed in the picker, when the provider says.
        val shownName: String = if (state is State.Done) {
            runCatching { DocumentFile.fromSingleUri(app, target)?.name }.getOrNull() ?: task.displayName
        } else {
            task.displayName
        }
        active.remove(task.key, task)
        task.update(state)
        withContext(Dispatchers.Main) {
            if (!observed(task.key)) {
                val text: String? = when (state) {
                    is State.Done -> app.getString(R.string.save_toast_saved_format, shownName)
                    is State.Failed -> app.getString(R.string.save_toast_failed_format, shownName)
                    else -> null
                }
                if (text != null) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
