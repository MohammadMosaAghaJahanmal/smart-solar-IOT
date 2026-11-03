package com.virtualcamerapro.overlay

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest gesture wins, bounded to 15 renderer updates/s. The camera Binder and
 * health reads share this worker, never the UI thread. Pending gestures take
 * precedence over idle health reads. No work survives closing the controls.
 */
internal class TransformDispatcher(
    private val apply: (SourceTransform) -> Unit,
    private val readPhase: () -> String,
    private val onClosed: () -> Unit,
    private val result: (SourceTransform, String?) -> Unit,
    private val phaseResult: (String) -> Unit
) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "VcamOverlayTransform") }
    private val next = AtomicReference<SourceTransform?>(null)
    private val scheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var lastStarted = 0L

    init { worker.scheduleWithFixedDelay({ pollPhase() }, 0, 3, TimeUnit.SECONDS) }

    fun hasPending() = scheduled.get() || next.get() != null
    fun submit(value: SourceTransform) {
        if (closed.get()) return
        next.set(value)
        schedule()
    }
    private fun schedule() {
        if (closed.get() || !scheduled.compareAndSet(false, true)) return
        val elapsed = System.nanoTime() - lastStarted
        val delay = (TimeUnit.MILLISECONDS.toNanos(67) - elapsed).coerceAtLeast(0)
        try { worker.schedule({ dispatch() }, delay, TimeUnit.NANOSECONDS) }
        catch (_: RejectedExecutionException) { scheduled.set(false) }
    }
    private fun dispatch() {
        try {
            val value = next.getAndSet(null)
            if (!closed.get() && value != null) {
                lastStarted = System.nanoTime()
                val error = try { apply(value); null }
                    catch (error: Exception) { error.message ?: "Camera update failed" }
                if (!closed.get()) runCatching { result(value, error) }
            }
        } finally {
            scheduled.set(false)
            if (next.get() != null) schedule()
        }
    }
    private fun pollPhase() {
        if (closed.get() || hasPending()) return
        val phase = runCatching { readPhase() }.getOrDefault("unavailable")
        if (!closed.get()) runCatching { phaseResult(phase) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        next.set(null)
        worker.execute { onClosed() }
        worker.shutdown()
    }
}
