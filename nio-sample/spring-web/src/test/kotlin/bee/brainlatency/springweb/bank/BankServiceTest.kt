package bee.brainlatency.springweb.bank

import bee.brainlatency.springweb.bank.application.BankService
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.springframework.boot.test.context.SpringBootTest
import kotlin.system.measureTimeMillis

@SpringBootTest
class BankServiceTest(
    private val sut: BankService,
) : StringSpec({

    "each request blocks until the previous one finishes" {
        // mock server responds with 1 second delay
        val elapsed = measureTimeMillis { sut.getBanks(listOf("김", "정", "은")) }

        elapsed shouldBeGreaterThanOrEqualTo 12_000
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}
