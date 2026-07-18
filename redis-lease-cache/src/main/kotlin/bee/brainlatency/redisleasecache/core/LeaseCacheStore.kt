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
 *
 * A store that itself fails -- as opposed to losing a fenced race, which the return
 * values express -- must throw [LeaseStoreException]. The adapter is the one that knows
 * what its client's failures look like, so the translation lives there, and the core
 * can rely on every operation either answering in domain terms or raising that one
 * domain failure.
 */
interface LeaseCacheStore<V : Any> {
    /** Atomically return the decoded entry at [key], or write [leaseToken] (living [leaseTtl]) and return it. */
    fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry<V>

    /**
     * Frame and write [value] at [key] (living [valueTtl]) only if it still holds
     * [leaseToken]. Returns whether the write landed: `false` means the lease was lost
     * (expired and taken over, or evicted), so the entry was left alone.
     */
    fun publish(key: String, leaseToken: LeaseToken, value: V?, valueTtl: Duration): Boolean

    /** Delete [key] only if it still holds [leaseToken], releasing an in-flight lease. */
    fun release(key: String, leaseToken: LeaseToken)

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean
}
