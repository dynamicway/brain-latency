package bee.brainlatency.springcoroutine.bank.infrastructure.external

import bee.brain_latency.nio_sample.mydata.domain.Transaction
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
class BankWebClient(private val webClient: WebClient) {

    suspend fun getAccountIds(orgCode: String): List<Long> =
        webClient.get()
            .uri("/accounts/{orgCode}", orgCode)
            .retrieve()
            .awaitBody()

    suspend fun getTransactions(orgCode: String, accountId: Long): List<Transaction> =
        webClient.get()
            .uri("/{orgCode}/transactions/{accountId}", orgCode, accountId)
            .retrieve()
            .awaitBody()
}
