package bee.brainlatency.redisleasecache.core

/**
 * A decoded cache entry: either a stored value of type [V] or a held load lease.
 *
 * [V] is covariant (`out`) because an entry only ever hands a value *out* (via
 * [Value.value]) and never takes one in, and because [Held] carries no value at all --
 * as `LeaseCacheEntry<Nothing>` it slots into any `LeaseCacheEntry<V>` without a phantom
 * type parameter or a cast at the decode site.
 */
sealed interface LeaseCacheEntry<out V> {
    /** A cached value, or `null` for a negatively cached miss. */
    data class Value<out V>(val value: V?) : LeaseCacheEntry<V>

    /** A load lease is held by someone; who is an opaque byte-level detail. */
    class Held(private val heldToken: LeaseToken) : LeaseCacheEntry<Nothing> {
        /** True if this held lease is the one [token] identifies (i.e. ours). */
        fun isHeldBy(token: LeaseToken): Boolean = heldToken.matches(token)
    }
}
