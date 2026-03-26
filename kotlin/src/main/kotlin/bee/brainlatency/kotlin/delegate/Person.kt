package bee.brainlatency.kotlin.delegate

class Person(
    age: Long,
    salary: Long
) {
    var age by PersonObserver(age)
    var salary by PersonObserver2(salary)
}