package bee.brainlatency.redisleasecache.core

import org.slf4j.LoggerFactory
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
 * [LeaseCacheCodec] and its [LeaseCacheSerializer] strategy), and the storage I/O
 * are all the concrete store's concern. This class deals only in domain terms: the
 * granted loader publishes with a compare-and-set on its lease token -- write the
 * value only if the key still holds it -- which both releases the lease and fences the
 * write. A slow "zombie" loader whose lease already expired (and was taken over) fails
 * the CAS and cannot overwrite the newer holder's fresh entry.
 *
 * The same fence also catches a narrower race: the key is evicted (e.g. by a writer
 * invalidating it) right after we were granted the lease but before our publish lands.
 * Nobody takes the lease over in that case, so instead of handing our caller a value
 * that may already be stale relative to whatever the evict was reacting to, the granted
 * loader re-runs -- bounded by the same [waitTimeout] a waiter is bounded by -- so a
 * settled race gets us a value that actually lands in the cache. If the race keeps
 * recurring until the deadline, we give up retrying and return the last value loaded
 * rather than fail the caller outright: the loader itself never failed, so there is no
 * [LeaseCacheLoadException] to throw, only a value we couldn't cache.
 *
 * ## Telling a cache failure from an origin failure
 *
 * Every failure surfaces as a typed [LeaseCacheException] whose subtype says *where* it
 * came from, so callers never have to guess whether the cache tier or the backing origin
 * is at fault:
 *  - [LeaseCacheLoadException] -- the caller's own `valueLoader` (the origin read) threw.
 *  - [LeaseCacheStoreException] -- a [LeaseCacheStore] operation (the cache I/O) threw.
 *  - [LeaseCacheWaitTimeoutException] -- a waiter gave up after [waitTimeout] *and* the
 *    wait was diagnosed as an origin bottleneck (see below).
 *
 * A pure waiter -- one that never held the lease -- can't observe the holder's loader
 * directly, so when it times out it can't say outright whether the origin load is slow
 * or the cache store itself is slow. It infers this from how the wait was spent: the
 * cumulative time its own [LeaseCacheStore.getOrAcquire] round-trips took, as a share of
 * the whole wait. If store round-trips ate at least [storeStallShare] of the wait, the
 * *cache tier* is the bottleneck -- single-flight coordination is degraded, but the
 * origin is presumably healthy, so we fail open and load from the origin directly rather
 * than fail the caller. Otherwise the *origin* load is the bottleneck (leases keep being
 * acquired but nothing gets published in time); piling another origin read on would only
 * add load to something already struggling, so we fail closed with a
 * [LeaseCacheWaitTimeoutException] and let the caller decide.
 *
 * It is name-agnostic and stateless beyond its config, so a single instance backs every
 * named cache: an adapter (e.g. the Spring-facing `TransactionAwareEvictCache`) owns the
 * cache name and hands down already-namespaced keys. It exposes only the two operations
 * the lease protocol actually has: a single-flight [get] with a loader, and an
 * immediate [evict]. Mapping [LeaseCacheException] onto a framework's exception contract
 * is the adapter's job.
 */
class LeaseCache<V : Any>(
    private val store: LeaseCacheStore<V>,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration = leaseTtl.multipliedBy(2),
    // Share of a timed-out wait that must have been spent in store round-trips for the
    // timeout to be blamed on the cache tier (fail open to the origin) rather than the
    // origin load (fail closed). Must be in (0.0, 1.0].
    private val storeStallShare: Double = 0.5,
) {

    init {
        require(storeStallShare > 0.0 && storeStallShare <= 1.0) {
            "storeStallShare must be in (0.0, 1.0] but was $storeStallShare"
        }
    }

    // Single-flight load: atomically read the entry or acquire the load lease, then
    // act on the resulting state (hit / hit-null / granted / loading). While another
    // loader holds the lease we poll: each retry re-runs the same atomic step, so
    // the moment the value lands we return it, and if the lease expires instead
    // (the loader died) we are granted and take over.
    fun get(key: String, valueLoader: () -> V?): V? {
        val startNanos = System.nanoTime()
        val deadline = startNanos + waitTimeout.toNanos()
        // Cumulative time our own getOrAcquire round-trips took, used at timeout to tell
        // a slow cache tier from a slow origin.
        var storeWaitNanos = 0L
        while (true) {
            val leaseToken = cacheStore("newLease", key) { store.newLease() }

            val entryStart = System.nanoTime()
            val entry = cacheStore("getOrAcquire", key) { store.getOrAcquire(key, leaseToken, leaseTtl) }
            storeWaitNanos += System.nanoTime() - entryStart

            when (entry) {
                is LeaseCacheEntry.Value -> return entry.value

                is LeaseCacheEntry.Held -> {
                    if (entry.isHeldBy(leaseToken)) {
                        val (loaded, published) = loadAndPublish(key, leaseToken, valueLoader)
                        if (published || System.nanoTime() >= deadline) return loaded
                        // Fenced out -- most likely an evict raced our publish rather
                        // than a takeover (a takeover would show up as a Value entry on
                        // the next loop instead). The value we hold may already be
                        // stale, so don't trust it yet: re-acquire and reload instead of
                        // returning it outright.
                        sleepBeforeRepoll()
                        continue
                    }
                    if (System.nanoTime() >= deadline) {
                        return onWaitTimeout(key, System.nanoTime() - startNanos, storeWaitNanos, valueLoader)
                    }
                    sleepBeforeRepoll()
                }
            }
        }
    }

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean = cacheStore("evict", key) { store.evict(key) }

    // A pure waiter has run out of patience. It never held the lease, so it can only
    // infer why: if its own store round-trips ate most of the wall-clock, the cache tier
    // is the bottleneck -- coordination is degraded but the origin is presumably fine, so
    // fail open and read the origin directly. Otherwise the origin load is the bottleneck
    // (leases kept turning over with nothing published), so fail closed rather than add
    // another origin read to something already struggling.
    private fun onWaitTimeout(key: String, elapsedNanos: Long, storeWaitNanos: Long, valueLoader: () -> V?): V? {
        val elapsed = Duration.ofNanos(elapsedNanos)
        val storeWait = Duration.ofNanos(storeWaitNanos)
        val storeShare = if (elapsedNanos > 0) storeWaitNanos.toDouble() / elapsedNanos else 0.0
        if (storeShare >= storeStallShare) {
            log.warn(
                "lease cache store appears degraded for key [{}]: store round-trips took {} of {} wait; loading from origin directly",
                key, storeWait, elapsed,
            )
            return try {
                valueLoader()
            } catch (ex: Throwable) {
                throw LeaseCacheLoadException(key, ex)
            }
        }
        throw LeaseCacheWaitTimeoutException(key, elapsed, storeWait)
    }

    // Jittered so a herd of waiters woken by the same miss doesn't re-poll in lockstep.
    private fun sleepBeforeRepoll() {
        val base = pollInterval.toMillis()
        Thread.sleep(base / 2 + Random.nextLong(base + 1))
    }

    // We hold the load lease. Fetch, then publish with a token-fenced CAS: the value
    // lands only if the key still holds our lease token, so a zombie loader whose
    // lease expired -- or was evicted out from under it -- can't clobber (or
    // resurrect) a fresher entry. The caller decides what to do when it doesn't land.
    private fun loadAndPublish(key: String, leaseToken: LeaseToken, valueLoader: () -> V?): Pair<V?, Boolean> {
        val loaded: V? = try {
            valueLoader()
        } catch (ex: Throwable) {
            // Best-effort release (CAS-del, fenced like publish) so a waiter takes over
            // immediately instead of waiting out leaseTtl. If the store itself is failing
            // the release fails too, but that must not mask the loader failure we're
            // surfacing -- the lease just expires via its TTL instead.
            try {
                store.release(key, leaseToken)
            } catch (_: Throwable) {
                // swallowed: lease expires via leaseTtl
            }
            throw LeaseCacheLoadException(key, ex)
        }
        val published = cacheStore("publish", key) { store.publish(key, leaseToken, loaded, valueTtl) }
        return loaded to published
    }

    // Runs a store operation, translating any failure into a LeaseCacheStoreException so
    // a cache-tier fault is never mistaken for an origin (loader) fault upstream.
    private inline fun <T> cacheStore(operation: String, key: String, block: () -> T): T =
        try {
            block()
        } catch (ex: Throwable) {
            throw LeaseCacheStoreException(operation, key, ex)
        }

    private companion object {
        private val log = LoggerFactory.getLogger(LeaseCache::class.java)
    }
}

/**
 * Base type for every failure [LeaseCache.get] raises. Its subtype pins down the source
 * -- the origin loader ([LeaseCacheLoadException]), the cache store
 * ([LeaseCacheStoreException]), or an origin-bottleneck wait timeout
 * ([LeaseCacheWaitTimeoutException]) -- so callers can catch broadly and branch precisely.
 */
sealed class LeaseCacheException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** The caller's `valueLoader` (the origin read) threw; any load lease has already been released. */
class LeaseCacheLoadException(key: String, cause: Throwable) :
    LeaseCacheException("value loader failed for key [$key]", cause)

/** A [LeaseCacheStore] operation (the cache I/O) threw -- a cache-tier fault, not an origin one. */
class LeaseCacheStoreException(operation: String, key: String, cause: Throwable) :
    LeaseCacheException("cache store operation [$operation] failed for key [$key]", cause)

/**
 * A waiter gave up after the wait timeout, having diagnosed the *origin* load as the
 * bottleneck (store round-trips were only a small share of the wait). Carries the
 * measured [waited] and [storeWait] so the caller can log or act on the diagnosis.
 */
class LeaseCacheWaitTimeoutException(key: String, val waited: Duration, val storeWait: Duration) :
    LeaseCacheException(
        "timed out after $waited waiting for another loader to publish key [$key]; " +
            "store round-trips took only $storeWait, so the origin load is the bottleneck",
        null,
    )
