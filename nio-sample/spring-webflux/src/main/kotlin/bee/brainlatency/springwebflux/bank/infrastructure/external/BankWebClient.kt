package bee.brainlatency.springwebflux.bank.infrastructure.external

import bee.brain_latency.nio_sample.mydata.domain.Transaction
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux

@Component
class BankWebClient(private val webClient: WebClient) {

    fun getAccountIds(orgCode: String): Flux<Long> =
        webClient.get()
            .uri("/accounts/{orgCode}", orgCode)
            .retrieve()
            .bodyToFlux()

    fun getTransactions(orgCode: String, accountId: Long): Flux<Transaction> =
        webClient.get()
            .uri("/{orgCode}/transactions/{accountId}", orgCode, accountId)
            .retrieve()
            .bodyToFlux()
}
