package bee.brainlatency.springweb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@EnableFeignClients
@SpringBootApplication
class SpringWebApplication

fun main(args: Array<String>) {
    runApplication<SpringWebApplication>(*args)
}
