package bee.brainlatency.kotlin.delegate

import io.kotest.core.spec.style.StringSpec

class PersonTest : StringSpec({

    "test - 1" {
        val sut = Person(1, 2)

        sut.age
        sut.salary

        sut.age = 1
        sut.age = 2
        sut.salary = 3
    }

})