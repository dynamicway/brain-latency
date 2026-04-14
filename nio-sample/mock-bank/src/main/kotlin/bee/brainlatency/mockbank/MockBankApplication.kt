package bee.brainlatency.mockbank

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockBankApplication

fun main(args: Array<String>) {
    runApplication<MockBankApplication>(*args)
}
