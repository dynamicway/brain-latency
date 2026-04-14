package bee.brainlatency.springweb.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Account
import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springweb.bank.infrastructure.external.BankClient
import org.springframework.stereotype.Service

@Service
class BankService(
    private val client: BankClient
) {
    fun getBanks(orgCodes: List<String>): GetBanksResponse {
        val banks = orgCodes.map { orgCode ->
            val accounts = client.getAccountIds(orgCode)
                .map { accountId -> Account(accountId, client.getTransactions(orgCode, accountId)) }
            Bank(orgCode, accounts)
        }

        return GetBanksResponse(banks)
    }

}