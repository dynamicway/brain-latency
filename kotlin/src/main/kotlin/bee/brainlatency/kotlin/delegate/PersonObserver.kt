package bee.brainlatency.kotlin.delegate

import kotlin.properties.ObservableProperty
import kotlin.reflect.KProperty

class PersonObserver(initialValue: Any) : ObservableProperty<Any>(initialValue) {
    override fun beforeChange(
        property: KProperty<*>,
        oldValue: Any,
        newValue: Any
    ): Boolean {
        return oldValue != newValue
    }

    override fun afterChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
        println("afterChange $oldValue -> $newValue")
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Any {
        println("""getValue: ${property}""")
        return super.getValue(thisRef, property)
    }
}