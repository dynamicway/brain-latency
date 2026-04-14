package bee.brainlatency.springwebvt.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Account
import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springwebvt.bank.infrastructure.external.BankRestClient
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class BankService(private val client: BankRestClient) {

    fun getBanks(orgCodes: List<String>): List<Bank> {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = orgCodes.map { orgCode ->
                executor.submit<Bank> { getBank(orgCode) }
            }
            return futures.map { it.get() }
        }
    }

    private fun getBank(orgCode: String): Bank {
        val accountIds = client.getAccountIds(orgCode)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = accountIds.map { accountId ->
                executor.submit<Account> {
                    Account(accountId, client.getTransactions(orgCode, accountId))
                }
            }
            return Bank(orgCode, futures.map { it.get() })
        }
    }
}
