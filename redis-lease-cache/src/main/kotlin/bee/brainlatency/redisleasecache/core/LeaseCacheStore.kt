package bee.brainlatency.redisleasecache.core

import java.time.Duration

/**
 * The storage interface behind [LeaseCache]: implementations own the client, the atomic
 * script, key/duration marshalling, and the byte framing (e.g. the Spring Data Redis
 * `RedisTemplateLeaseCacheStore`) -- the core only speaks in these domain terms.
 *
 * Every operation taking a lease token must be fenced on it: it takes effect only
 * while the key still holds that exact token, which is what keeps a zombie loader
 * from clobbering a newer holder. A store that fails outright -- as opposed to losing
 * a fenced race, which the return values express -- throws [LeaseStoreException].
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
