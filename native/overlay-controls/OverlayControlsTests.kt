package com.virtualcamerapro.overlay

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private var passed = 0
private fun test(name: String, operation: () -> Unit) {
    operation()
    passed++
    println("PASS $name")
}
private fun CountDownLatch.ready() = check(await(4, TimeUnit.SECONDS)) { "Worker did not finish" }

fun main() {
    test("pinch cycles retain fractional zoom and position") {
        val initial = SourceTransform(2.125, -0.625, 0.375)
        var actual = initial
        repeat(1000) { actual = actual.scale(1.005).scale(1.0 / 1.005) }
        check(abs(actual.zoom - initial.zoom) < 1e-10)
        check(actual.panX == initial.panX && actual.panY == initial.panY)
    }
    test("extreme drags and pinch factors stay within renderer limits") {
        val initial = SourceTransform(2.0, 0.0, 0.0)
        check(initial.move(Double.MAX_VALUE, -Double.MAX_VALUE) == SourceTransform(2.0, 1.0, -1.0))
        check(initial.scale(Double.MAX_VALUE).zoom == 8.0)
        check(initial.scale(Double.MIN_VALUE).zoom == 1.0)
        check(initial.scale(Double.NaN) == initial && initial.scale(-1.0) == initial)
        check(initial.move(Double.POSITIVE_INFINITY, 0.0) == initial)
    }
    test("a blocked renderer conflates a gesture burst to the latest request") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<SourceTransform>()
        val expected = SourceTransform(3.0, 0.8, -0.6)
        val dispatcher = TransformDispatcher(apply = { value ->
            applied.add(value)
            if (applied.size == 1) { entered.countDown(); release.ready() }
            if (value == expected) finished.countDown()
        }, readPhase = { "active" }, onClosed = {}, result = { _, _ -> }, phaseResult = {})
        try {
            dispatcher.submit(SourceTransform.ORIGINAL)
            entered.ready()
            val start = System.nanoTime()
            repeat(500) { dispatcher.submit(SourceTransform(2.0 + it / 1000.0, 0.0, 0.0)) }
            dispatcher.submit(expected)
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250) { "Gesture submit blocked on camera work" }
            release.countDown()
            finished.ready()
            check(applied.size == 2 && applied.last() == expected) { "Stale gesture updates were delivered: ${applied.size}" }
        } finally { release.countDown(); dispatcher.close() }
    }
    test("renderer updates respect the production 15 fps bound") {
        val completed = Semaphore(0)
        val times = CopyOnWriteArrayList<Long>()
        val dispatcher = TransformDispatcher(apply = { times.add(System.nanoTime()) }, readPhase = { "active" },
            onClosed = {}, result = { _, _ -> completed.release() }, phaseResult = {})
        try {
            repeat(5) {
                dispatcher.submit(SourceTransform(1.0 + it * 0.1, 0.0, 0.0))
                check(completed.tryAcquire(4, TimeUnit.SECONDS))
            }
            check(times.size == 5)
            times.zipWithNext().forEach { (before, after) ->
                check(TimeUnit.NANOSECONDS.toMillis(after - before) >= 60) { "Updates exceeded the frame rate bound" }
            }
        } finally { dispatcher.close() }
    }
    test("reset wins over queued movement and reaches the source adapter") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reset = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<SourceTransform>()
        val dispatcher = TransformDispatcher(apply = { value ->
            applied.add(value)
            if (applied.size == 1) { entered.countDown(); release.ready() }
            else if (value == SourceTransform.ORIGINAL) reset.countDown()
        }, readPhase = { "active" }, onClosed = {}, result = { _, _ -> }, phaseResult = {})
        try {
            dispatcher.submit(SourceTransform(3.0, -1.0, 1.0)); entered.ready()
            repeat(100) { dispatcher.submit(SourceTransform(4.0, 0.2, -0.2)) }
            dispatcher.submit(SourceTransform.ORIGINAL)
            release.countDown(); reset.ready()
            check(applied.size == 2 && applied.last() == SourceTransform.ORIGINAL)
        } finally { release.countDown(); dispatcher.close() }
    }
    test("closing drops queued work, closes once, and never blocks the UI caller") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<SourceTransform>()
        val closedCount = java.util.concurrent.atomic.AtomicInteger()
        val dispatcher = TransformDispatcher(apply = { value -> applied.add(value); entered.countDown(); release.ready() },
            readPhase = { "active" }, onClosed = { closedCount.incrementAndGet(); closed.countDown() }, result = { _, _ -> }, phaseResult = {})
        try {
            dispatcher.submit(SourceTransform.ORIGINAL); entered.ready()
            dispatcher.submit(SourceTransform(4.0, 0.5, 0.5))
            val start = System.nanoTime()
            dispatcher.close(); dispatcher.close()
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250)
            dispatcher.submit(SourceTransform(8.0, 1.0, 1.0))
            release.countDown(); closed.ready()
            check(applied == listOf(SourceTransform.ORIGINAL) && closedCount.get() == 1)
        } finally { release.countDown(); dispatcher.close() }
    }
    test("a camera update error is reported without stranding later requests") {
        val complete = Semaphore(0)
        val failures = CopyOnWriteArrayList<String>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val dispatcher = TransformDispatcher(apply = { if (count.incrementAndGet() == 1) error("camera disconnected") },
            readPhase = { "active" }, onClosed = {}, result = { _, error -> failures.add(error ?: "success"); complete.release() }, phaseResult = {})
        try {
            dispatcher.submit(SourceTransform.ORIGINAL); check(complete.tryAcquire(4, TimeUnit.SECONDS))
            dispatcher.submit(SourceTransform(2.0, 0.0, 0.0)); check(complete.tryAcquire(4, TimeUnit.SECONDS))
            check(failures == listOf("camera disconnected", "success"))
        } finally { dispatcher.close() }
    }
    test("a disappearing UI callback cannot stall the transform queue") {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val dispatcher = TransformDispatcher(apply = { if (count.incrementAndGet() == 2) second.countDown() },
            readPhase = { "active" }, onClosed = {}, result = { _, _ -> first.countDown(); error("view closed") }, phaseResult = {})
        try {
            dispatcher.submit(SourceTransform.ORIGINAL); first.ready()
            dispatcher.submit(SourceTransform(2.0, 0.0, 0.0)); second.ready()
        } finally { dispatcher.close() }
    }
    test("phase polling runs on the worker and publishes the real adapter result") {
        val caller = Thread.currentThread()
        val phase = CountDownLatch(1)
        val dispatcher = TransformDispatcher(apply = {}, readPhase = {
            check(Thread.currentThread() !== caller)
            "stopped"
        }, onClosed = {}, result = { _, _ -> }, phaseResult = { actual -> check(actual == "stopped"); phase.countDown() })
        try { phase.ready() } finally { dispatcher.close() }
    }
    println("OK $passed overlay controls tests")
}
