package com.jrs8205.appletvremote.protocol.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TouchPumpTest {

    @Test
    fun mergesQueuedHoldSamplesButKeepsPressAndRelease() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sent = CopyOnWriteArrayList<TouchSample>()
            val gate = CompletableDeferred<Unit>()
            val firstSendStarted = CompletableDeferred<Unit>()
            val pump = TouchPump(scope) { sample ->
                if (!firstSendStarted.isCompleted) {
                    firstSendStarted.complete(Unit)
                    gate.await()
                }
                sent += sample
            }
            pump.submit(TouchSample(TouchPhase.PRESS, 500, 500))
            firstSendStarted.await()
            pump.submit(TouchSample(TouchPhase.HOLD, 510, 500))
            pump.submit(TouchSample(TouchPhase.HOLD, 520, 500))
            pump.submit(TouchSample(TouchPhase.HOLD, 530, 500))
            pump.submit(TouchSample(TouchPhase.RELEASE, 530, 500))
            gate.complete(Unit)
            while (sent.size < 3) delay(10)
            delay(50)
            assertEquals(
                listOf(
                    TouchSample(TouchPhase.PRESS, 500, 500),
                    TouchSample(TouchPhase.HOLD, 530, 500),
                    TouchSample(TouchPhase.RELEASE, 530, 500),
                ),
                sent.toList(),
            )
            pump.close()
            scope.cancel()
        }
    }

    @Test
    fun deliversEverySampleInOrderWhenSenderKeepsUp() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sent = CopyOnWriteArrayList<TouchSample>()
            val pump = TouchPump(scope) { sent += it }
            val samples = listOf(
                TouchSample(TouchPhase.PRESS, 500, 500),
                TouchSample(TouchPhase.HOLD, 501, 500),
                TouchSample(TouchPhase.RELEASE, 501, 500),
            )
            for (sample in samples) {
                pump.submit(sample)
                while (sent.size < samples.indexOf(sample) + 1) delay(5)
            }
            assertEquals(samples, sent.toList())
            pump.close()
            scope.cancel()
        }
    }

    @Test
    fun sendFailuresDoNotStopTheQueue() = runBlocking {
        withTimeout(10_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sent = CopyOnWriteArrayList<TouchSample>()
            val pump = TouchPump(scope) { sample ->
                if (sample.phase == TouchPhase.PRESS) throw IllegalStateException("boom")
                sent += sample
            }
            pump.submit(TouchSample(TouchPhase.PRESS, 500, 500))
            pump.submit(TouchSample(TouchPhase.RELEASE, 500, 500))
            while (sent.isEmpty()) delay(5)
            assertEquals(listOf(TouchSample(TouchPhase.RELEASE, 500, 500)), sent.toList())
            pump.close()
            scope.cancel()
        }
    }
}
