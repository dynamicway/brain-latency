package bee.brainlatency.springweb.bank.infrastructure.external

import bee.brain_latency.nio_sample.mydata.domain.Transaction
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient("bank-service")
interface BankClient {

    @GetMapping("/accounts/{orgCode}")
    fun getAccountIds(@PathVariable orgCode: String): List<Long>

    @GetMapping("/{orgCode}/transactions/{accountId}")
    fun getTransactions(
        @PathVariable orgCode: String,
        @PathVariable accountId: Long
    ): List<Transaction>

}
