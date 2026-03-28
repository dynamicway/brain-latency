package bee.brainlatency.kotlin.coroutine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ConcurrencyTest : StringSpec({

    "Local variable should be safe from concurrent access" {
        launch(Dispatchers.Default) {
            var x = 0
            repeat(10000) { x++ }

            x shouldBe 10000
        }
    }

    "Shared variable should suffer from race conditions" {
        var x = 0

        coroutineScope {
            repeat(10000) {
                launch(Dispatchers.Default) { x++ }
            }
        }

        x shouldBeInRange 9000..9999
    }

})