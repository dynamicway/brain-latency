package bee.brainlatency.springwebflux.bank.controller

import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springwebflux.bank.application.BankService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class BankController(private val service: BankService) {

    @GetMapping("/banks")
    fun getBanks(@RequestParam orgCodes: List<String>): Mono<List<Bank>> =
        service.getBanks(orgCodes)
}
