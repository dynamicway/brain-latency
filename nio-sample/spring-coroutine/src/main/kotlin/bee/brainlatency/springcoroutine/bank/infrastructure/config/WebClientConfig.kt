package bee.brainlatency.springcoroutine.bank.infrastructure.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

@Configuration
class WebClientConfig {

    @Bean
    fun webClient(@Value("\${bank.base-url}") baseUrl: String): WebClient {
        val provider = ConnectionProvider.builder("bank")
            .maxConnections(10_000)
            .build()

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(HttpClient.create(provider)))
            .build()
    }
}
