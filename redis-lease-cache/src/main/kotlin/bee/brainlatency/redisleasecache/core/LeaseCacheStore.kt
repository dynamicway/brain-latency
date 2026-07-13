package bee.brainlatency.redisleasecache.core

import java.time.Duration

/**
 * The storage port behind [LeaseCache]: the domain-level operations the lease protocol
 * needs -- mint a lease token, get-or-acquire an entry, publish a value, release,
 * evict. How they reach the store -- which client, how any atomic script is run, how
 * keys/durations are marshalled, and how entries are framed on the wire -- is entirely
 * the concrete adapter's concern (e.g. the Spring Data Redis
 * `RedisTemplateLeaseCacheStore`), so the core never touches a client, framework, or
 * byte-framing type. Every operation taking a lease token must be fenced on it: it
 * takes effect only while the key still holds that exact token, which is what keeps
 * zombie loaders from clobbering a newer holder.
 */
interface LeaseCacheStore {

    /** A fresh, uniquely-identified lease token to attempt acquisition with. */
    fun newLease(): ByteArray

    /** Atomically return the decoded entry at [key], or write [leaseToken] (living [leaseTtl]) and return it. */
    fun getOrAcquire(key: String, leaseToken: ByteArray, leaseTtl: Duration): LeaseCacheEntry

    /** Frame and write [value] at [key] (living [valueTtl]) only if it still holds [leaseToken]. */
    fun publish(key: String, leaseToken: ByteArray, value: Any?, valueTtl: Duration)

    /** Delete [key] only if it still holds [leaseToken], releasing an in-flight lease. */
    fun release(key: String, leaseToken: ByteArray)

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean
}
