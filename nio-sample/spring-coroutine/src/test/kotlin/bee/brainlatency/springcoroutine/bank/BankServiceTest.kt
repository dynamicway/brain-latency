package bee.brainlatency.springcoroutine.bank

import bee.brainlatency.springcoroutine.bank.application.BankService
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.longs.shouldBeLessThan
import org.springframework.boot.test.context.SpringBootTest
import kotlin.system.measureTimeMillis

@SpringBootTest
class BankServiceTest(private val sut: BankService) : StringSpec({

    "async/awaitAll scatters all requests concurrently" {
        val elapsed = measureTimeMillis { sut.getBanks(listOf("김", "정", "은")) }

        elapsed shouldBeLessThan 3_000
    }

    "event loop based concurrency handles large number of orgs without thread exhaustion" {
        val orgCodes = (1..2_000).map { it.toString() }
        val elapsed = measureTimeMillis { sut.getBanks(orgCodes) }
        println("elapsed: ${elapsed}ms")

        elapsed shouldBeLessThan 3_000
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}
