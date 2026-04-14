package bee.brainlatency.springwebflux.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Account
import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springwebflux.bank.infrastructure.external.BankWebClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class BankService(private val client: BankWebClient) {

    fun getBanks(orgCodes: List<String>): Mono<List<Bank>> =
        Flux.fromIterable(orgCodes)
            .flatMap(::getBank, orgCodes.size)
            .collectList()

    fun getBanksSequentially(orgCodes: List<String>): Mono<List<Bank>> =
        Flux.fromIterable(orgCodes)
            .concatMap { getBank(it) }
            .collectList()

    private fun getBank(orgCode: String): Mono<Bank> =
        client.getAccountIds(orgCode)
            .flatMap { accountId ->
                client.getTransactions(orgCode, accountId)
                    .collectList()
                    .map { transactions -> Account(accountId, transactions) }
            }
            .collectList()
            .map { accounts -> Bank(orgCode, accounts) }
}
