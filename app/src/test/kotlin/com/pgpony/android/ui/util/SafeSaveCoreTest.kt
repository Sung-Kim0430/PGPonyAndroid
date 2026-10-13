// SafeSaveCoreTest.kt
// PGPony Android 4.7.0 (item 37, #73): the save copy reports its progress,
// stops when cancelled, and never reports a save that did not finish without
// first running its cleanup (which deletes the partial document).

package com.pgpony.android.ui.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafeSaveCoreTest {

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }

    /** Records close, and can fail after [failAfter] bytes. */
    private class SinkSpy(private val failAfter: Long = Long.MAX_VALUE) : OutputStream() {
        val data = ByteArrayOutputStream()
        var closes = 0
        override fun write(b: Int) {
            if (data.size() + 1L > failAfter) throw IOException("ENOSPC (No space left on device)")
            data.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (data.size() + len.toLong() > failAfter) throw IOException("ENOSPC (No space left on device)")
            data.write(b, off, len)
        }
        override fun close() { closes++ }
    }

    // ── Temporary names ──────────────────────────────────────────────

    @Test
    fun partName_isHiddenAndEndsInPart() {
        assertEquals(".report.pdf.part", SafeSaveCore.partName("report.pdf"))
        assertEquals(".photo (1).jpg.gpg.part", SafeSaveCore.partName("photo (1).jpg.gpg"))
    }

    @Test
    fun partName_dropsPathsAndBlankNames() {
        assertEquals(".evil.txt.part", SafeSaveCore.partName("../../evil.txt"))
        assertEquals(".x.part", SafeSaveCore.partName("a\\b\\x"))
        assertEquals(".output.part", SafeSaveCore.partName("   "))
    }

    @Test
    fun partName_capsVeryLongNames() {
        val long = "a".repeat(400)
        val part = SafeSaveCore.partName(long)
        assertEquals(".".length + 200 + ".part".length, part.length)
        assertTrue(part.startsWith(".") && part.endsWith(".part"))
    }

    // ── The copy ─────────────────────────────────────────────────────

    @Test
    fun copy_writesEveryByte_reportsProgress_andClosesOnce() {
        val src = payload(3 * 1024 * 1024 + 123)
        val sink = SinkSpy()
        val progress = ArrayList<Long>()
        var cleaned = false
        var committed = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { ByteArrayInputStream(src) },
            openSink = { sink },
            isCancelled = { false },
            onProgress = { progress.add(it) },
            cleanup = { cleaned = true },
            commit = { committed = true },
        )
        assertEquals(SafeSaveCore.Outcome.Done(src.size.toLong()), outcome)
        assertArrayEquals(src, sink.data.toByteArray())
        assertEquals(1, sink.closes)
        assertTrue(committed)
        assertFalse(cleaned)
        // Rate-limited, rising, and landing on the full size.
        assertTrue(progress.size >= 2)
        assertEquals(progress.sorted(), progress)
        assertEquals(src.size.toLong(), progress.last())
    }

    @Test
    fun copy_cancelStopsEarly_andCleansUp() {
        val src = payload(8 * 1024 * 1024)
        val sink = SinkSpy()
        var cleaned = false
        var committed = false
        var cancel = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { ByteArrayInputStream(src) },
            openSink = { sink },
            isCancelled = { cancel },
            onProgress = { n -> if (n >= 1024L * 1024) cancel = true },
            cleanup = { cleaned = true },
            commit = { committed = true },
        )
        assertEquals(SafeSaveCore.Outcome.Cancelled, outcome)
        assertTrue(cleaned)
        assertFalse(committed)
        assertTrue(sink.data.size() < src.size)
        assertEquals(1, sink.closes)
    }

    @Test
    fun copy_cancelledBeforeStart_writesNothing() {
        var opened = false
        var cleaned = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { ByteArrayInputStream(payload(10)) },
            openSink = { opened = true; SinkSpy() },
            isCancelled = { true },
            onProgress = { },
            cleanup = { cleaned = true },
        )
        assertEquals(SafeSaveCore.Outcome.Cancelled, outcome)
        assertFalse(opened)
        assertTrue(cleaned)
    }

    @Test
    fun copy_writeFailure_cleansUp_andReportsTheCause() {
        val sink = SinkSpy(failAfter = 200_000)
        var cleaned = false
        var committed = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { ByteArrayInputStream(payload(1_000_000)) },
            openSink = { sink },
            isCancelled = { false },
            onProgress = { },
            cleanup = { cleaned = true },
            commit = { committed = true },
        )
        assertTrue(outcome is SafeSaveCore.Outcome.Failed)
        assertTrue((outcome as SafeSaveCore.Outcome.Failed).cause is IOException)
        assertTrue(cleaned)
        assertFalse(committed)
        assertEquals(1, sink.closes)
    }

    @Test
    fun copy_missingSource_cleansUp_withoutOpeningTheSink() {
        var opened = false
        var cleaned = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { throw java.io.FileNotFoundException("gone") },
            openSink = { opened = true; SinkSpy() },
            isCancelled = { false },
            onProgress = { },
            cleanup = { cleaned = true },
        )
        assertTrue(outcome is SafeSaveCore.Outcome.Failed)
        assertFalse(opened)
        assertTrue(cleaned)
    }

    @Test
    fun copy_failedCommit_isAFailure_andCleansUp() {
        var cleaned = false
        val outcome = SafeSaveCore.runCopy(
            openInput = { ByteArrayInputStream(payload(1000)) },
            openSink = { SinkSpy() },
            isCancelled = { false },
            onProgress = { },
            cleanup = { cleaned = true },
            commit = { throw IOException("Rename failed") },
        )
        assertTrue(outcome is SafeSaveCore.Outcome.Failed)
        assertTrue(cleaned)
    }

    @Test
    fun copy_closesTheSourceEveryTime() {
        var closed = 0
        val src = object : InputStream() {
            override fun read(): Int = -1
            override fun close() { closed++ }
        }
        SafeSaveCore.runCopy(
            openInput = { src },
            openSink = { throw IOException("no sink") },
            isCancelled = { false },
            onProgress = { },
            cleanup = { },
        )
        assertEquals(1, closed)
    }

    // ── Writer saves (zip, MIME envelope) ────────────────────────────

    @Test
    fun writer_thatClosesTheStreamItself_isFine() {
        val sink = SinkSpy()
        val outcome = SafeSaveCore.run(
            openSink = { sink },
            write = { out -> out.write(payload(5000)); out.close() },
            isCancelled = { false },
            onProgress = { },
            cleanup = { },
        )
        assertEquals(SafeSaveCore.Outcome.Done(5000L), outcome)
        assertEquals(1, sink.closes)
        assertEquals(5000, sink.data.size())
    }

    @Test
    fun writer_shortOfTheMinimum_isAFailure_andCleansUp() {
        var cleaned = false
        val outcome = SafeSaveCore.run(
            openSink = { SinkSpy() },
            write = { out -> out.write(payload(100)) },
            isCancelled = { false },
            onProgress = { },
            cleanup = { cleaned = true },
            minExpectedBytes = 1000L,
        )
        assertTrue(outcome is SafeSaveCore.Outcome.Failed)
        assertTrue((outcome as SafeSaveCore.Outcome.Failed).cause is ShortWriteException)
        assertTrue(cleaned)
    }

    @Test
    fun progressStream_buffersSmallWrites_andCountsWhatReachesTheSink() {
        val sink = SinkSpy()
        val reported = ArrayList<Long>()
        val s = ProgressOutputStream(sink, bufferSize = 16, reportEvery = 32, onProgress = { reported.add(it) })
        repeat(100) { s.write(it) }
        s.write(payload(40), 0, 40)
        s.close()
        s.reportFinal()
        assertEquals(140L, s.count)
        assertEquals(140, sink.data.size())
        assertEquals(140L, reported.last())
        s.close()
        assertEquals(1, sink.closes)
    }
}
