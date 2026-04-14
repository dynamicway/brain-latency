package bee.brainlatency.springcoroutine

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringCoroutineApplication

fun main(args: Array<String>) {
    runApplication<SpringCoroutineApplication>(*args)
}
