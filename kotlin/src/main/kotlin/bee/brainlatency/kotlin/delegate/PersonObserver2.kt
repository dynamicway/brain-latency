package bee.brainlatency.kotlin.delegate

import kotlin.reflect.KProperty

class PersonObserver2<T>(
    private var value: T
) {
    operator fun getValue(thisRef: T, property: KProperty<*>): T = value
    operator fun setValue(thisRef: T, property: KProperty<*>, newValue: T) {
        println("$value -> $newValue")
        value = newValue
    }
}