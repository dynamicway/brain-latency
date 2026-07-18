package bee.brainlatency.redisleasecache.core

import java.time.Duration
import kotlin.random.Random

/**
 * Cross-instance stampede protection: on a miss exactly one caller is *granted* a
 * short-lived load lease and runs the loader, while the rest poll until the value is
 * published or the lease expires and one of them takes over. Waiting is bounded by
 * [waitTimeout].
 *
 * This class only orchestrates the states; storage, byte framing, and the lease token
 * itself live behind the [LeaseCacheStore] interface. It is name-agnostic and stateless
 * beyond its config, so a single instance backs every named cache -- an adapter (e.g.
 * `TransactionAwareEvictCache`) owns the cache name and hands down namespaced keys.
 *
 * Exposes a single-flight [get] and an immediate [evict]. Failures surface as a
 * [LeaseCacheException]: the origin refused ([OriginLoadException]), the store is down
 * ([LeaseStoreException]), or nobody published in time ([LeaseWaitTimeoutException]).
 */
class LeaseCache<V : Any>(
    private val store: LeaseCacheStore<V>,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration = leaseTtl.multipliedBy(2),
) {

    /**
     * Single-flight load: atomically reads the entry or acquires the load lease, then
     * acts on the resulting state (hit / hit-null / granted / loading). While another
     * loader holds the lease we poll -- each retry re-runs the same atomic step, so
     * the moment the value lands we return it, and if the lease expires instead (the
     * loader died) we are granted and take over.
     *
     * The loop has exactly two exits -- a return, or running out the deadline in the
     * while condition. A branch that neither returns nor throws simply falls to the
     * bottom and re-enters: the not-ours branch after its poll sleep, and the granted
     * branch when its token-fenced publish loses the CAS. Losing that CAS means our
     * lease is gone (expired and taken over, or evicted out from under us), so the
     * value we loaded was never cached -- starting over, rather than returning it, is
     * what makes a racing evict actually take effect, because the next pass either
     * hits whatever the winner published or re-acquires and reloads.
     */
    fun get(key: String, valueLoader: () -> V?): V? {
        val deadline = System.nanoTime() + waitTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            val leaseToken = LeaseToken.new()

            when (val entry = store.getOrAcquire(key, leaseToken, leaseTtl)) {
                is LeaseCacheEntry.Value -> return entry.value

                is LeaseCacheEntry.Held ->
                    if (entry.isHeldBy(leaseToken)) {
                        val loaded = loadOrRelease(key, leaseToken, valueLoader)
                        if (store.publish(key, leaseToken, loaded, valueTtl)) return loaded
                    } else {
                        sleepBeforeRepoll()
                    }
            }
        }
        throw LeaseWaitTimeoutException(key, waitTimeout)
    }

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean = store.evict(key)

    /** Jittered so a herd of waiters woken by the same miss doesn't re-poll in lockstep. */
    private fun sleepBeforeRepoll() {
        val base = pollInterval.toMillis()
        Thread.sleep(base / 2 + Random.nextLong(base + 1))
    }

    /**
     * Runs the caller's loader; on failure releases the lease (CAS-del, fenced like
     * publish) so a waiter takes over immediately instead of waiting out [leaseTtl].
     * Whether that release lands or loses its own race is immaterial to the caller --
     * either way the loader is what failed, so the origin failure is what surfaces.
     * Only the store *erroring* changes the story (the store throws its own
     * [LeaseStoreException] for that): then the coordination layer is down, not the
     * origin, and the loader's exception rides along as suppressed.
     */
    private fun loadOrRelease(key: String, leaseToken: LeaseToken, valueLoader: () -> V?): V? {
        try {
            return valueLoader()
        } catch (loadEx: Throwable) {
            try {
                store.release(key, leaseToken)
            } catch (storeEx: LeaseStoreException) {
                storeEx.addSuppressed(loadEx)
                throw storeEx
            }
            throw OriginLoadException(key, loadEx)
        }
    }
}
