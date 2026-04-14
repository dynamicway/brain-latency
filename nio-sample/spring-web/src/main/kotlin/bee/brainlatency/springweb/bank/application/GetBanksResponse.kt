package bee.brainlatency.springweb.bank.application

import bee.brain_latency.nio_sample.mydata.domain.Bank

class GetBanksResponse(
    val banks: List<Bank>
)