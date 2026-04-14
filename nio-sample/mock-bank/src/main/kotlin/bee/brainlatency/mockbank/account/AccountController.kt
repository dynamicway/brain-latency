package bee.brainlatency.mockbank.account

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
class AccountController {

    @GetMapping("/{orgCode}")
    fun getAccounts(@PathVariable orgCode: String): List<Long> {
        Thread.sleep(1000)

        return listOf(1, 2, 3)
    }
}
