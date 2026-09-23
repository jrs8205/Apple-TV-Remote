package com.jrs8205.appletvremote.protocol.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Feeds touch samples to the connection without ever blocking the UI. When the socket is slower
 * than the finger, queued HOLD samples collapse into the newest one; PRESS and RELEASE always go out.
 */
class TouchPump(scope: CoroutineScope, private val send: suspend (TouchSample) -> Unit) {

    private val queue = Channel<TouchSample>(Channel.UNLIMITED)

    private val worker = scope.launch {
        for (first in queue) {
            var current = first
            while (true) {
                val next = queue.tryReceive().getOrNull() ?: break
                if (current.phase == TouchPhase.HOLD && next.phase == TouchPhase.HOLD) {
                    current = next
                } else {
                    deliver(current)
                    current = next
                }
            }
            deliver(current)
        }
    }

    fun submit(sample: TouchSample) {
        queue.trySend(sample)
    }

    fun close() {
        queue.close()
        worker.cancel()
    }

    private suspend fun deliver(sample: TouchSample) {
        try {
            send(sample)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The connection layer reports failures through its own state; a lost sample is not fatal.
        }
    }
}
