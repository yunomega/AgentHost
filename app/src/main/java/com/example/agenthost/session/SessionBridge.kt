package com.example.agenthost.session

import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SessionBridge {
    private const val TAG = "SessionBridge"
    private val waiters = ConcurrentHashMap<String, CompletableFuture<SessionAttachAck>>()

    fun registerWaiter(sessionId: String) {
        val newFuture = CompletableFuture<SessionAttachAck>()
        val previous = waiters.put(sessionId, newFuture)
        previous?.cancel(true)
        Log.i(TAG, "registerWaiter sessionId=$sessionId")
    }

    fun reportAttached(ack: SessionAttachAck): Boolean {
        val future = waiters[ack.sessionId]
        if (future == null) {
            Log.w(
                TAG,
                "reportAttached ignored sessionId=${ack.sessionId} taskId=${ack.taskId} " +
                    "displayId=${ack.displayId} reason=NO_WAITER component=${ack.component}"
            )
            return false
        }
        val accepted = future.complete(ack)
        val message = "reportAttached sessionId=${ack.sessionId} taskId=${ack.taskId} " +
            "displayId=${ack.displayId} accepted=$accepted component=${ack.component}"
        if (accepted) {
            Log.i(TAG, message)
        } else {
            Log.w(TAG, message)
        }
        return accepted
    }

    fun await(sessionId: String, timeoutMs: Long): SessionAttachAck? {
        val future = waiters[sessionId] ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (t: Throwable) {
            Log.e(TAG, "await failed sessionId=$sessionId", t)
            null
        } finally {
            waiters.remove(sessionId)
        }
    }

    fun clear(sessionId: String) {
        waiters.remove(sessionId)?.cancel(true)
        Log.i(TAG, "clear sessionId=$sessionId")
    }
}
