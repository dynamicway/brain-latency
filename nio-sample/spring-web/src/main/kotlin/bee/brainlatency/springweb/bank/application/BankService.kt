package bee.brainlatency.springweb.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Account
import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springweb.bank.infrastructure.external.BankClient
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

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

    fun getBanksByAsync(
        orgCodes: List<String>,
        executor: Executor = ForkJoinPool.commonPool()
    ): GetBanksResponse {
        val futures = orgCodes.map { orgCode ->
            CompletableFuture.supplyAsync({ client.getAccountIds(orgCode) }, executor)
                .thenApply { accountIds ->
                    val accountFutures = accountIds.map { accountId ->
                        CompletableFuture.supplyAsync({
                            Account(accountId, client.getTransactions(orgCode, accountId))
                        }, executor)
                    }
                    val accounts = CompletableFuture.allOf(*accountFutures.toTypedArray())
                        .thenApply { accountFutures.map { it.join() } }
                        .join()
                    Bank(orgCode, accounts)
                }
        }

        return GetBanksResponse(
            CompletableFuture.allOf(*futures.toTypedArray())
                .thenApply { futures.map { it.join() } }
                .join()
        )
    }

}