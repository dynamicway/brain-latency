package bee.brainlatency.springwebvt.bank.infrastructure.external

import bee.brain_latency.nio_sample.mydata.domain.Transaction
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class BankRestClient(private val restClient: RestClient) {

    fun getAccountIds(orgCode: String): List<Long> =
        restClient.get()
            .uri("/accounts/{orgCode}", orgCode)
            .retrieve()
            .body<List<Long>>() ?: emptyList()

    fun getTransactions(orgCode: String, accountId: Long): List<Transaction> =
        restClient.get()
            .uri("/{orgCode}/transactions/{accountId}", orgCode, accountId)
            .retrieve()
            .body<List<Transaction>>() ?: emptyList()
}
