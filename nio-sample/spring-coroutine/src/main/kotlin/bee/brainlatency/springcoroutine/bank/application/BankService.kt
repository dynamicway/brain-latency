package bee.brainlatency.springcoroutine.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Account
import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springcoroutine.bank.infrastructure.external.BankWebClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service

@Service
class BankService(private val client: BankWebClient) {

    suspend fun getBanks(orgCodes: List<String>): List<Bank> = coroutineScope {
        orgCodes.map { orgCode -> async { getBank(orgCode) } }
            .awaitAll()
    }

    private suspend fun getBank(orgCode: String): Bank = coroutineScope {
        val accountIds = client.getAccountIds(orgCode)

        val accounts = accountIds
            .map { accountId ->
                async {
                    Account(accountId, client.getTransactions(orgCode, accountId))
                }
            }
            .awaitAll()

        Bank(orgCode, accounts)
    }
}
