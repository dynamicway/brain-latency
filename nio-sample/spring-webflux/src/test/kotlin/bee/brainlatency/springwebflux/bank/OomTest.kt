package bee.brainlatency.springwebflux.bank

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import reactor.core.publisher.Flux

class OomTest : StringSpec({

    "streaming processes each item without accumulating in heap" {
        // 300,000 items × 1KB = ~300MB total data volume
        // but only one item lives in memory at a time
        val count = Flux.range(0, 300_000)
            .map { ByteArray(1_024) }
            .count()
            .block()

        count shouldBe 300_000
    }

    "collectList() holds all items in heap simultaneously causing OOM" {
        // same 300MB but collected into a List before returning
        // exceeds -Xmx200m heap (framework already uses ~100MB)
        val largeStream = Flux.range(0, 300_000)
            .map { ByteArray(1_024) }

        shouldThrow<OutOfMemoryError> {
            largeStream.collectList().block()
        }
    }

})
