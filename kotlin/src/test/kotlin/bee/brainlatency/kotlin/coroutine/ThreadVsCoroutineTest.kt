package bee.brainlatency.kotlin.coroutine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thread vs Coroutine comparison experiments
 *
 * Why Threads are expensive:
 *  1. Creation cost - JVM Thread maps 1:1 to an OS Thread, allocating stack memory (default 512KB~1MB)
 *  2. Context switching - OS kernel intervenes to save/restore registers and stack
 *  3. Scheduling limit - OS caps the number of threads (typically a few thousand)
 *
 * Coroutines:
 *  - Run hundreds of thousands concurrently on a small fixed thread pool (cooperative scheduling)
 *  - Suspension happens without kernel involvement → near-zero switching cost
 *  - State is saved in a Continuation object on the heap instead of a stack
 */
class ThreadVsCoroutineTest : StringSpec({

    val n = 10_000

    // ── Experiment 1: Creation time ────────────────────────────────────────────
    // Thread asks the OS for stack memory and creates a kernel thread.
    // Coroutine just puts a lambda + Continuation object on the heap.
    "Thread creation is slower than Coroutine creation" {
        val threadTime = measureTimeMillis {
            val latch = CountDownLatch(n)
            repeat(n) {
                Thread {
                    latch.countDown()
                }.start()
            }
            latch.await()
        }

        val coroutineTime = measureTimeMillis {
            coroutineScope {
                repeat(n) {
                    launch(Dispatchers.Default) { /* no-op */ }
                }
            }
        }

        println("Thread    $n: ${threadTime}ms")
        println("Coroutine $n: ${coroutineTime}ms")

        coroutineTime shouldBeLessThan threadTime
    }

    // ── Experiment 2: Context switching cost ──────────────────────────────────
    // Creating far more threads than CPU cores forces the OS into preemptive switching.
    //   → Each switch saves/restores registers and stack, and invalidates CPU cache (~1–10μs)
    //
    // Dispatchers.Default uses exactly core-count OS threads.
    //   → No preemptive switching; cache stays warm.
    //
    // Giving both the same CPU workload and comparing wall time reveals the switching overhead.
    "Oversubscribed threads are slower than coroutines due to context switching" {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        // Enough to force frequent preemption
        val workerCount = cpuCores * 8

        fun busyWork(): Long {
            var result = 0L
            repeat(20_000_000) { result += it }
            return result
        }

        val threadTime = measureTimeMillis {
            val latch = CountDownLatch(workerCount)
            repeat(workerCount) {
                Thread {
                    busyWork()
                    latch.countDown()
                }.start()
            }
            latch.await()
        }

        val coroutineTime = measureTimeMillis {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        busyWork()
                    }
                }
            }
        }

        println("CPU cores: $cpuCores, workers: $workerCount (cores × 8)")
        println("Thread    $workerCount CPU-bound tasks: ${threadTime}ms")
        println("Coroutine $workerCount CPU-bound tasks: ${coroutineTime}ms")
        println("Switching overhead: ${threadTime - coroutineTime}ms")

        coroutineTime shouldBeLessThan threadTime
    }

    // ── Experiment 3: Throughput under IO wait ─────────────────────────────────
    // Thread holds its OS thread during sleep → throughput is capped by thread count limit.
    // Coroutine suspends and releases the thread → the same thread is reused for others.
    "Coroutines handle more concurrent IO waits than threads" {
        val taskCount = 100_000
        val delayMs = 10L

        val threadTime = measureTimeMillis {
            val latch = CountDownLatch(taskCount)
            repeat(taskCount) {
                Thread {
                    Thread.sleep(delayMs) // simulates blocking IO
                    latch.countDown()
                }.start()
            }
            latch.await()
        }

        val coroutineTime = measureTimeMillis {
            coroutineScope {
                repeat(taskCount) {
                    launch(Dispatchers.IO) {
                        delay(delayMs.milliseconds) // non-blocking wait
                    }
                }
            }
        }

        println("Thread    $taskCount tasks (${delayMs}ms wait each): ${threadTime}ms")
        println("Coroutine $taskCount tasks (${delayMs}ms wait each): ${coroutineTime}ms")

        coroutineTime shouldBeLessThan threadTime
    }

})
