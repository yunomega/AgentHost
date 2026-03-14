package com.example.agenthost.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class TimedInputStreamReaderTest {
    @Test
    fun returnsBytesWhenReadCompletesWithinTimeout() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = TimedInputStreamReader(executor)
            val result = reader.readAll(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 200)
            assertArrayEquals(byteArrayOf(1, 2, 3), result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(expected = TimeoutException::class)
    fun closesInputWhenReadTimesOut() {
        val executor = Executors.newSingleThreadExecutor()
        val blockingStream = BlockingCloseAwareInputStream()
        try {
            val reader = TimedInputStreamReader(executor)
            try {
                reader.readAll(blockingStream, 50)
            } finally {
                assertTrue(blockingStream.closedByReader.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private class BlockingCloseAwareInputStream : InputStream() {
        val closedByReader = AtomicBoolean(false)

        @Volatile
        private var closed = false

        override fun read(): Int {
            while (!closed) {
                Thread.sleep(10)
            }
            return -1
        }

        override fun close() {
            closedByReader.set(true)
            closed = true
        }
    }
}
