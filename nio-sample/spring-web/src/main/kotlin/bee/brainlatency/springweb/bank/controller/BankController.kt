package bee.brainlatency.springweb.bank.controller

import bee.brainlatency.springweb.bank.application.BankService
import bee.brainlatency.springweb.bank.application.GetBanksResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class BankController(
    private val service: BankService
) {

    @GetMapping("/banks")
    fun getBanks(@RequestParam orgCodes: List<String>): GetBanksResponse {
        return service.getBanks(orgCodes)
    }

}