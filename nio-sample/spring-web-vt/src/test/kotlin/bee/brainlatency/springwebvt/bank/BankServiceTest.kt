package bee.brainlatency.springwebvt.bank

import bee.brainlatency.springwebvt.bank.application.BankService
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.longs.shouldBeLessThan
import org.springframework.boot.test.context.SpringBootTest
import kotlin.system.measureTimeMillis

@SpringBootTest
class BankServiceTest(private val sut: BankService) : StringSpec({

    "virtual threads handle concurrent requests without thread exhaustion" {
        // each virtual thread blocks on HTTP, but carrier threads are never starved
        val orgCodes = (1..20).map { it.toString() }
        val elapsed = measureTimeMillis { sut.getBanks(orgCodes) }
        println("elapsed: ${elapsed}ms")

        elapsed shouldBeLessThan 3_000
    }

    "newVirtualThreadPerTaskExecutor never deadlocks unlike fixed thread pool" {
        // fixed pool deadlocks when outer tasks exhaust threads waiting for inner tasks
        // virtual threads are unbounded — inner tasks always get a thread
        val orgCodes = (1..100).map { it.toString() }
        val elapsed = measureTimeMillis { sut.getBanks(orgCodes) }
        println("elapsed: ${elapsed}ms")

        elapsed shouldBeLessThan 3_000
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}
