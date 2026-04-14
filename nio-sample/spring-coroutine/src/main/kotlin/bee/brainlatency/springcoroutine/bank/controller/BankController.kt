package bee.brainlatency.springcoroutine.bank.controller

import bee.brain_latency.nio_sample.mydata.domain.Bank
import bee.brainlatency.springcoroutine.bank.application.BankService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class BankController(private val service: BankService) {

    @GetMapping("/banks")
    suspend fun getBanks(@RequestParam orgCodes: List<String>): List<Bank> =
        service.getBanks(orgCodes)
}
