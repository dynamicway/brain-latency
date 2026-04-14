package bee.brainlatency.mockbank.transaction

import bee.brain_latency.nio_sample.mydata.domain.Transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/{orgCode}/transactions")
class TransactionController {

    @GetMapping("/{accountId}")
    fun getTransactions(
        @PathVariable orgCode: String,
        @PathVariable accountId: Long,
    ): List<Transaction> {
        Thread.sleep(10)

        return listOf(Transaction(1, "brain-buster", 100_000_000.0), Transaction(2, "nuclear", 300_000_000.0))
    }
}
