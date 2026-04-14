package bee.brainlatency.springwebflux

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringWebfluxApplication

fun main(args: Array<String>) {
    runApplication<SpringWebfluxApplication>(*args)
}
