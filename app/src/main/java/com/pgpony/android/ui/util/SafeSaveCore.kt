// SafeSaveCore.kt
// PGPony Android 4.7.0 (item 37, #73): the copy behind every Save.
//
// Saving a large decrypted file used to be one copyTo() inside a
// screen-scoped coroutine, with nothing on screen while it ran. Leaving the
// screen cancelled nothing (the copy never suspends) but leaving the app could
// get the process killed mid-copy, and either way a cut-short file stayed
// behind under the name the user chose, looking complete.
//
// This is the part with no Android in it, so it runs as a plain JVM unit test
// (SafeSaveCoreTest): the chunked copy, the byte count reported upward, the
// cancel check on every chunk, and the rule that a save which did not finish
// calls its cleanup (which deletes the partial document) before it reports.
// SafeSave.kt wires it to content URIs, DocumentFile and the process scope.

package com.pgpony.android.ui.util

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/** 4.7.0 (item 37): thrown out of the copy once the user cancels a save. */
class SaveCancelledException : InterruptedIOException("Save cancelled")

/**
 * 4.7.0 (item 37): a writer finished without error but produced fewer bytes
 * than the save must contain (a provider that truncated, for instance), so it
 * is treated as a failure and the partial document is removed.
 */
class ShortWriteException(val written: Long, val expected: Long) :
    IOException("Short write: $written of $expected bytes")

internal object SafeSaveCore {

    /** Same chunk size as the streaming encrypt and decrypt paths. */
    const val CHUNK_SIZE = 64 * 1024

    /** Progress fires at most once per this many bytes (plus once at the end). */
    const val REPORT_EVERY = 512L * 1024

    sealed class Outcome {
        data class Done(val bytes: Long) : Outcome()
        data object Cancelled : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
    }

    /**
     * 4.7.0 (item 37): the temporary name a save into a folder is written
     * under, ".<name>.part". Hidden (leading dot) and with an extension no app
     * opens, so an interrupted save never looks like the finished file; it is
     * renamed to [finalName] only after the last byte and the close.
     */
    fun partName(finalName: String): String {
        val base = finalName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .ifBlank { "output" }
        // Most file systems cap a name at 255 bytes; leave room for the
        // dot and ".part" on a very long name.
        val capped = if (base.length > 200) base.take(200) else base
        return ".$capped.part"
    }

    /**
     * Copy [input] to [output] in [CHUNK_SIZE] chunks until end of stream,
     * checking [isCancelled] before every chunk. Returns the bytes copied.
     * Closes neither stream.
     */
    fun copyChunked(
        input: InputStream,
        output: OutputStream,
        isCancelled: () -> Boolean = { false },
    ): Long {
        val buf = ByteArray(CHUNK_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw SaveCancelledException()
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                output.write(buf, 0, n)
                total += n
            }
        }
        return total
    }

    /**
     * Run one save: open the sink, hand it to [write] through a counting,
     * cancel-checking, buffering stream, close it, then [commit] (a rename
     * for a save into a folder; nothing for a picked document).
     *
     * On any failure or a cancel the sink is closed and [cleanup] runs before
     * this returns, so the caller can delete what was written. [cleanup] and
     * [commit] failures never escape: a failed commit is a failed save (and
     * gets the cleanup), a failed cleanup is swallowed.
     *
     * [write] may close the stream itself (ZipPackaging.writeSingleEntry
     * does); closing twice is harmless here.
     */
    fun run(
        openSink: () -> OutputStream,
        write: (OutputStream) -> Unit,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        cleanup: () -> Unit,
        commit: () -> Unit = {},
        minExpectedBytes: Long = 0L,
        reportEvery: Long = REPORT_EVERY,
    ): Outcome {
        var stream: ProgressOutputStream? = null
        return try {
            if (isCancelled()) throw SaveCancelledException()
            val s = ProgressOutputStream(openSink(), CHUNK_SIZE, reportEvery, isCancelled, onProgress)
            stream = s
            write(s)
            s.close()
            s.reportFinal()
            if (s.count < minExpectedBytes) throw ShortWriteException(s.count, minExpectedBytes)
            // A cancel that lands after the last byte still wins: the user
            // asked for no file, so the finished one goes too.
            if (isCancelled()) throw SaveCancelledException()
            commit()
            Outcome.Done(s.count)
        } catch (t: Throwable) {
            stream?.abort()
            runCatching { cleanup() }
            if (t is SaveCancelledException || isCancelled()) Outcome.Cancelled else Outcome.Failed(t)
        }
    }

    /**
     * [run] for a plain copy: [openInput] is opened first (before the sink,
     * so a source that is already gone fails before anything is written, and
     * still gets the cleanup), copied to the end, and always closed.
     */
    fun runCopy(
        openInput: () -> InputStream,
        openSink: () -> OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        cleanup: () -> Unit,
        commit: () -> Unit = {},
        reportEvery: Long = REPORT_EVERY,
    ): Outcome {
        val input: InputStream = try {
            openInput()
        } catch (t: Throwable) {
            runCatching { cleanup() }
            return Outcome.Failed(t)
        }
        return try {
            run(
                openSink = openSink,
                write = { out -> copyChunked(input, out, isCancelled) },
                isCancelled = isCancelled,
                onProgress = onProgress,
                cleanup = cleanup,
                commit = commit,
                reportEvery = reportEvery,
            )
        } finally {
            runCatching { input.close() }
        }
    }
}

/**
 * 4.7.0 (item 37): buffers writes into [bufferSize] chunks, counts the bytes
 * that reach [delegate], reports them to [onProgress] at most once per
 * [reportEvery] bytes, and throws [SaveCancelledException] before writing a
 * chunk once [isCancelled] is true. Close is idempotent and closes
 * [delegate]; [abort] closes it without writing what is still buffered.
 *
 * The buffer is here rather than in a BufferedOutputStream on top, so a
 * writer that closes its stream (the zip wrapper does) closes this class
 * exactly once, and [count] is what the destination actually received.
 */
internal class ProgressOutputStream(
    private val delegate: OutputStream,
    bufferSize: Int = SafeSaveCore.CHUNK_SIZE,
    private val reportEvery: Long = SafeSaveCore.REPORT_EVERY,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (Long) -> Unit = {},
) : OutputStream() {

    private val buf = ByteArray(bufferSize)
    private var pos = 0
    private var closed = false
    private var lastReported = 0L

    /** Bytes handed to [delegate] so far. */
    var count: Long = 0L
        private set

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }

    private fun checkCancelled() {
        if (isCancelled()) throw SaveCancelledException()
    }

    private fun advance(n: Int) {
        count += n
        if (count - lastReported >= reportEvery) {
            lastReported = count
            onProgress(count)
        }
    }

    private fun drain() {
        if (pos > 0) {
            checkCancelled()
            delegate.write(buf, 0, pos)
            advance(pos)
            pos = 0
        }
    }

    override fun write(b: Int) {
        ensureOpen()
        if (pos == buf.size) drain()
        buf[pos++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return
        if (len >= buf.size) {
            drain()
            checkCancelled()
            delegate.write(b, off, len)
            advance(len)
            return
        }
        if (len > buf.size - pos) drain()
        System.arraycopy(b, off, buf, pos, len)
        pos += len
    }

    override fun flush() {
        if (closed) return
        drain()
        delegate.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            drain()
            delegate.flush()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            delegate.close()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        }
        if (failure != null) throw failure
    }

    /** Close [delegate] without writing the buffered bytes; never throws. */
    fun abort() {
        if (closed) return
        closed = true
        pos = 0
        runCatching { delegate.close() }
    }

    /** Report the final count so a progress bar lands on 100%. */
    fun reportFinal() {
        if (count != lastReported) {
            lastReported = count
            onProgress(count)
        }
    }
}
