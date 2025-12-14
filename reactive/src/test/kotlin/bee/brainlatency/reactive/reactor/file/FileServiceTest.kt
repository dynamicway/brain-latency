package bee.brainlatency.reactive.reactor.file

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.atomic.AtomicLong

class FileServiceTest : StringSpec({

    "read does not block the caller thread" {
        val sut = FileService()
        val callerThread = Thread.currentThread().name
        var executionThread = ""

        // read operation should execute on a different thread
        sut.read("test.txt")
            .doOnNext { executionThread = Thread.currentThread().name }
            .block()

        // verify execution thread is different from caller thread
        executionThread shouldNotBe callerThread
    }

    "single thread handles multiple concurrent requests using reactor" {
        val sut = FileService()
        val count = AtomicLong()

        // submit 100 read requests
        repeat(100) {
            sut.read("test.txt").subscribe { count.incrementAndGet() }  // count successful requests
        }
        // wait 3s expecting concurrent execution (sequential would take 200s)
        Thread.sleep(3000)

        // all 100 requests should succeed
        count.get() shouldBe 100
    }

    "fails to complete all requests when exceeding thread pool and queue capacity" {
        val sut = FileService()
        val count = AtomicLong()

        // threadCap=100, queuedTaskCap=100 → max 200 concurrent requests
        // 300 requests → 100 will be rejected
        repeat(300) {
            sut.read("test.txt").subscribe { count.incrementAndGet() } // count successful requests
        }
        Thread.sleep(5000)

        // only 200 out of 300 requests should succeed
        count.get() shouldBe 200  // 200 succeeded, 100 rejected
    }

})