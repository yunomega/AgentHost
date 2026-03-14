package com.example.agenthost.platform

import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class TimedInputStreamReader(
    private val executor: ExecutorService
) {
    fun readAll(input: InputStream, timeoutMs: Long): ByteArray {
        val safeTimeoutMs = timeoutMs.coerceAtLeast(1L)
        val future: Future<ByteArray> = executor.submit<ByteArray> {
            input.use { stream -> stream.readBytes() }
        }
        return try {
            future.get(safeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            closeQuietly(input)
            throw e
        } catch (e: InterruptedException) {
            future.cancel(true)
            closeQuietly(input)
            throw e
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (_: Throwable) {
        }
    }
}
