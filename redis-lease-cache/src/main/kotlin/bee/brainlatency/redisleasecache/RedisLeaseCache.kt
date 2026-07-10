package bee.brainlatency.redisleasecache

import org.springframework.cache.Cache
import java.time.Duration
import java.util.concurrent.Callable

/**
 * A Redis-backed [Cache] that gives `@Cacheable(sync = true)` cross-instance
 * stampede protection: on a miss exactly one caller is *granted* a short-lived
 * load lease and runs the loader, while the rest see the key is *loading*. The
 * lease is purely an internal load lock -- it lives only for the duration of one
 * load and expires after [leaseTtl] if the loader dies.
 *
 * This class only orchestrates the states. Byte framing lives in [LeaseCacheCodec]
 * and Redis I/O in [LeaseCacheStore], so it deals in domain terms: the granted
 * loader publishes with a compare-and-set on its lease entry -- write the value only
 * if the key still holds it -- which both releases the lease and fences the write.
 * A slow "zombie" loader whose lease already expired (and was taken over) fails the
 * CAS and cannot overwrite the newer holder's fresh entry.
 *
 * This cache is usable only through `@Cacheable(sync = true)` -- [get] with a
 * `valueLoader`. The `sync = false` read/write path is rejected fail-fast: both the
 * plain value [get] and the tokenless [put] throw, so misapplying the cache surfaces
 * on the first call instead of silently working on hits and breaking on the first
 * miss. Typed [get] and [clear] are unsupported too.
 *
 * Eviction is likewise only supported with `@CacheEvict(beforeInvocation = false)`
 * (the default) -- evict after the method runs. `beforeInvocation = true` routes to
 * [evictIfPresent], which this cache rejects: evicting before invocation would race
 * the very load lease this cache exists to coordinate.
 */
class RedisLeaseCache(
    private val name: String,
    private val store: LeaseCacheStore,
    private val codec: LeaseCacheCodec,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
) : Cache {

    override fun getName(): String = name

    override fun getNativeCache(): Any = store

    // Single-flight load: atomically read the entry or acquire the load lease, then
    // act on the resulting state (hit / hit-null / granted / loading).
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        val leaseEntry = codec.newLease()
        val raw = store.getOrAcquire(redisKey(key), leaseEntry, leaseTtl)

        return when (val entry = codec.decode(raw)) {
            is LeaseCacheEntry.Value -> {
                @Suppress("UNCHECKED_CAST")
                entry.value as T?
            }

            is LeaseCacheEntry.Held ->
                if (entry.isHeldBy(leaseEntry)) {
                    loadAndPublish(key, leaseEntry, valueLoader)
                } else {
                    // another loader holds the lease -- someone else is loading.
                    // TODO: waiter polling. Re-run getOrAcquire with backoff until the
                    //       key flips to a value (loader finished) or the lease expires
                    //       (loader died -> we get granted and take over). No wait yet.
                    throw UnsupportedOperationException("another loader holds the lease for key [$key]; waiter polling not yet implemented")
                }
        }
    }

    private fun <T : Any> loadAndPublish(key: Any, leaseEntry: ByteArray, valueLoader: Callable<T>): T? {
        // We hold the load lease. Fetch, then publish with a token-fenced CAS: the
        // value lands only if the key still holds our lease entry, so a zombie loader
        // whose lease expired can't clobber the holder that took over. Either way we
        // return the loaded value to our own caller.
        // TODO: if valueLoader.call() throws, release the lease (CAS-del our entry) so
        //       a waiter takes over immediately instead of waiting out leaseTtl.
        val loaded: T? = valueLoader.call()
        store.publish(redisKey(key), leaseEntry, codec.valueEntry(loaded), valueTtl)
        return loaded
    }

    // A value may be written only by the loader that holds the lease, fencing the
    // write on its lease entry (see [loadAndPublish]). A tokenless put has no such
    // proof, so allowing it would let anyone overwrite the cache and defeat the lease.
    override fun put(key: Any, value: Any?) {
        throw UnsupportedOperationException("RedisLeaseCache does not support tokenless put(); values are published only by the granted loader")
    }

    override fun evict(key: Any) {
        store.evict(redisKey(key))
    }

    // evictIfPresent backs @CacheEvict(beforeInvocation = true) -- evict before the
    // method runs. Rejected fail-fast: an eviction that fires before invocation could
    // race a concurrent load lease acquisition for the same key.
    override fun evictIfPresent(key: Any): Boolean {
        throw UnsupportedOperationException("RedisLeaseCache does not support evictIfPresent(); use @CacheEvict(beforeInvocation = false), the default")
    }

    // The plain value getter is the `@Cacheable(sync = false)` read path, which this
    // cache does not support (that path would then call the unsupported put). Failing
    // fast here surfaces the misconfiguration on the very first call, rather than
    // silently working on hits and blowing up on the first miss.
    override fun get(key: Any): Cache.ValueWrapper? {
        throw UnsupportedOperationException("RedisLeaseCache only works via @Cacheable(sync = true) / get(key, valueLoader); the plain get(key) used by sync = false is unsupported")
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        throw UnsupportedOperationException("RedisLeaseCache only works via @Cacheable(sync = true) / get(key, valueLoader); typed get(key, type) is unsupported")
    }

    override fun clear() {
        throw UnsupportedOperationException("RedisLeaseCache does not support clear(); evict entries individually")
    }

    private fun redisKey(key: Any): String = "$name::$key"
}
