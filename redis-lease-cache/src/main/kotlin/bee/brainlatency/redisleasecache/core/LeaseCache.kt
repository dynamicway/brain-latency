package bee.brainlatency.redisleasecache.core

import java.time.Duration
import kotlin.random.Random

/**
 * The lease-token cache proper: cross-instance stampede protection, free of any
 * framework or Redis-client type. On a miss exactly one caller is *granted* a
 * short-lived load lease and runs the loader, while the rest poll until the value is
 * published -- or, if the lease expires because the loader died, until one of them is
 * granted and takes over. Waiting is bounded by [waitTimeout]. The lease is purely an
 * internal load lock -- it lives only for the duration of one load, is released the
 * moment the loader throws, and expires after [leaseTtl] only if the loader dies
 * without unwinding.
 *
 * This class only orchestrates the states; everything environment-specific lives
 * behind the [LeaseCacheStore] port -- minting a lease token, byte framing (via a
 * [LeaseCacheEntryCodec] and its [LeaseCacheValueSerializer] strategy), and the storage I/O
 * are all the concrete store's concern. This class deals only in domain terms: the
 * granted loader publishes with a compare-and-set on its lease token -- write the
 * value only if the key still holds it -- which both releases the lease and fences the
 * write. A slow "zombie" loader whose lease already expired (and was taken over) fails
 * the CAS and cannot overwrite the newer holder's fresh entry.
 *
 * It is name-agnostic and stateless beyond its config, so a single instance backs every
 * named cache: an adapter (e.g. the Spring-facing `TransactionAwareEvictCache`) owns the
 * cache name and hands down already-namespaced keys. It exposes only the two operations
 * the lease protocol actually has: a single-flight [get] with a loader, and an
 * immediate [evict]. Failures surface as a [LeaseCacheException] -- the origin refused
 * ([OriginLoadException]), the store is down ([LeaseStoreException]), or nobody
 * published in time ([LeaseWaitTimeoutException]); mapping those onto a framework's
 * exception contract is the adapter's job.
 */
class LeaseCache<V : Any>(
    private val store: LeaseCacheStore<V>,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration = leaseTtl.multipliedBy(2),
) {

    // Single-flight load: atomically read the entry or acquire the load lease, then
    // act on the resulting state (hit / hit-null / granted / loading). While another
    // loader holds the lease we poll: each retry re-runs the same atomic step, so
    // the moment the value lands we return it, and if the lease expires instead
    // (the loader died) we are granted and take over.
    //
    // Every path that does not return re-enters this loop through the deadline check,
    // so no amount of losing (to a poller, to an evict) can spin past waitTimeout.
    fun get(key: String, valueLoader: () -> V?): V? {
        val deadline = System.nanoTime() + waitTimeout.toNanos()
        while (true) {
            if (System.nanoTime() >= deadline) {
                throw LeaseWaitTimeoutException(key, waitTimeout)
            }
            val leaseToken = LeaseToken.new()

            when (val entry = store.getOrAcquire(key, leaseToken, leaseTtl)) {
                is LeaseCacheEntry.Value -> return entry.value

                is LeaseCacheEntry.Held -> {
                    if (!entry.isHeldBy(leaseToken)) {
                        // Someone else is loading; poll and re-read.
                        sleepBeforeRepoll()
                        continue
                    }
                    // We hold the lease: load, then publish under a token-fenced CAS.
                    val loaded = loadOrRelease(key, leaseToken, valueLoader)
                    if (publishFenced(key, leaseToken, loaded)) {
                        return loaded
                    }
                    // The CAS lost: our lease is gone (expired and taken over, or the key
                    // was evicted out from under us), so the value we hold was never
                    // cached. Start over rather than return it -- the next pass either
                    // hits whatever the winner published or re-acquires and reloads,
                    // which is what makes a racing evict actually take effect.
                }
            }
        }
    }

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean = store.evict(key)

    // Jittered so a herd of waiters woken by the same miss doesn't re-poll in lockstep.
    private fun sleepBeforeRepoll() {
        val base = pollInterval.toMillis()
        Thread.sleep(base / 2 + Random.nextLong(base + 1))
    }

    // Run the caller's loader; on failure release the lease (CAS-del, fenced like
    // publish) so a waiter takes over immediately instead of waiting out leaseTtl.
    // Whether that release lands or loses its own race is immaterial to the caller --
    // either way the loader is what failed, so the origin failure is what surfaces.
    // Only the store *erroring* changes the story: then the coordination layer is down,
    // not the origin, and the loader's exception rides along as suppressed.
    private fun loadOrRelease(key: String, leaseToken: LeaseToken, valueLoader: () -> V?): V? {
        try {
            return valueLoader()
        } catch (loadEx: Throwable) {
            try {
                store.release(key, leaseToken)
            } catch (storeEx: Throwable) {
                throw LeaseStoreException(key, storeEx).apply { addSuppressed(loadEx) }
            }
            throw OriginLoadException(key, loadEx)
        }
    }

    // Publish with a token-fenced CAS: the value lands only if the key still holds our
    // lease token, so a zombie loader whose lease expired can't clobber the holder that
    // took over. Returns whether the write landed; a store error is an outage, not a
    // lost race, so it surfaces rather than looking like a losing CAS.
    private fun publishFenced(key: String, leaseToken: LeaseToken, value: V?): Boolean =
        try {
            store.publish(key, leaseToken, value, valueTtl)
        } catch (storeEx: Throwable) {
            throw LeaseStoreException(key, storeEx)
        }
}
