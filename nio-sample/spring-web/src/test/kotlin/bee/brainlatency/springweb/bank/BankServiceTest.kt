package bee.brainlatency.springweb.bank

import bee.brainlatency.springweb.bank.application.BankService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

@SpringBootTest
class BankServiceTest(
    private val sut: BankService,
) : StringSpec({

    beforeSpec {
        // FeignHttpMessageConverters.initConvertersIfRequired() is not thread-safe.
        // Force eager initialization on the main thread before concurrent Feign calls.
        sut.getBanksByAsync(listOf("1"))
    }

    "each request blocks until the previous one finishes" {
        // mock server responds with 1 second delay
        val elapsed = measureTimeMillis { sut.getBanks(listOf("김", "정", "은")) }

        elapsed shouldBeGreaterThanOrEqualTo 12_000
    }

    "parallel async calls complete close to the slowest single request" {
        // all requests fire concurrently, so total time ≈ 1 request delay
        val elapsed = measureTimeMillis { sut.getBanksByAsync(listOf("김", "정", "은")) }

        elapsed shouldBeLessThan 3_000
    }

    "thread pool exhaustion degrades async performance" {
        // 20 orgs × (1 account call + 3 transaction calls) = 80 blocking tasks
        // far exceeds pool size, so tasks queue up and total time grows
        val orgCodes = (1..20).map { it.toString() }
        val elapsed = measureTimeMillis { sut.getBanksByAsync(orgCodes) }
        println("elapsed: ${elapsed}ms")

        elapsed shouldBeGreaterThan 3_000
    }

    "fixed thread pool deadlocks when outer tasks exhaust the pool waiting for inner tasks" {
        // pool size = orgCodes count → outer tasks fill all threads, each calling .join()
        // on inner tasks that can never run → deadlock
        val orgCodes = listOf("1", "2", "3")
        val fixedPool = Executors.newFixedThreadPool(orgCodes.size)

        val future = CompletableFuture.runAsync {
            sut.getBanksByAsync(orgCodes, fixedPool)
        }

        shouldThrow<TimeoutException> {
            future.get(5, TimeUnit.SECONDS)
        }

        fixedPool.shutdownNow()
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}
