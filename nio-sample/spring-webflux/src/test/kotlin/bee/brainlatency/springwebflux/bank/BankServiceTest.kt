package bee.brainlatency.springwebflux.bank

import bee.brainlatency.springwebflux.bank.application.BankService
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeLessThan
import org.springframework.boot.test.context.SpringBootTest
import reactor.test.StepVerifier
import kotlin.system.measureTimeMillis

@SpringBootTest
class BankServiceTest(
    private val sut: BankService,
) : StringSpec({

    "concatMap processes each org sequentially - total time accumulates" {
        // mock server responds with 1 second delay per call
        val elapsed = measureTimeMillis {
            StepVerifier.create(sut.getBanksSequentially(listOf("김", "정", "은")))
                .expectNextCount(1)
                .verifyComplete()
        }

        elapsed shouldBeGreaterThanOrEqualTo 12_000
    }

    "flatMap processes all orgs concurrently - total time close to slowest single org" {
        val elapsed = measureTimeMillis {
            StepVerifier.create(sut.getBanks(listOf("김", "정", "은")))
                .expectNextCount(1)
                .verifyComplete()
        }

        elapsed shouldBeLessThan 3_000
    }

    "flatMap handles large number of orgs without thread exhaustion" {
        // reactive doesn't consume a thread per request - event loop handles all I/O
        val orgCodes = (1..2_000).map { it.toString() }
        val elapsed = measureTimeMillis {
            StepVerifier.create(sut.getBanks(orgCodes))
                .expectNextCount(1)
                .verifyComplete()
        }
        println("elapsed: ${elapsed}ms")

        elapsed shouldBeLessThan 3_000
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}
