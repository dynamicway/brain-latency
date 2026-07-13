package bee.brainlatency.redisleasecache.core

import java.time.Duration

/**
 * The storage port behind [LeaseCache]: the four atomic operations the lease protocol
 * needs, expressed in domain terms -- an entry, a lease, a TTL. How they reach the
 * store -- which Redis client, how the Lua scripts are run, how keys and durations are
 * marshalled -- is the adapter's concern (e.g. the Spring Data Redis
 * `RedisTemplateLeaseCacheStore`), so the core never touches a client or framework
 * type. Every operation taking a lease entry must be fenced on it: it takes effect only
 * while the key still holds that exact entry, which is what keeps zombie loaders from
 * clobbering a newer holder.
 */
interface LeaseCacheStore {

    /** Atomically return the entry at [key], or write [leaseEntry] (living [leaseTtl]) and return it. */
    fun getOrAcquire(key: String, leaseEntry: ByteArray, leaseTtl: Duration): ByteArray

    /** Write [payload] at [key] (living [valueTtl]) only if it still holds [leaseEntry]. */
    fun publish(key: String, leaseEntry: ByteArray, payload: ByteArray, valueTtl: Duration)

    /** Delete [key] only if it still holds [leaseEntry], releasing an in-flight lease. */
    fun release(key: String, leaseEntry: ByteArray)

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean
}
