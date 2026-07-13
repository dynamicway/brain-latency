package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheLoadException
import org.springframework.cache.Cache
import java.util.concurrent.Callable

/**
 * The Spring [Cache] contract for a [LeaseCache]: a thin adapter that maps the
 * `@Cacheable` / `@CacheEvict` surface onto the lease-token protocol and rejects every
 * mode the protocol can't honour. All the stampede coordination -- the load lease,
 * polling, token-fenced publish -- lives in the [delegate]; this class only owns the
 * cache [name] (namespacing every key as `name::key`) and decides which Spring entry
 * points are legal. One [delegate] is shared across all named caches, since the name
 * lives here rather than in the engine.
 *
 * This cache is usable only through `@Cacheable(sync = true)` -- [get] with a
 * `valueLoader`, forwarded to the delegate. The `sync = false` read/write path is
 * rejected fail-fast: both the plain value [get] and the tokenless [put] throw, so
 * misapplying the cache surfaces on the first call instead of silently working on hits
 * and breaking on the first miss. Typed [get] and [clear] are unsupported too.
 *
 * Eviction is likewise only supported with `@CacheEvict(beforeInvocation = false)`
 * (the default) -- evict after the method runs. `beforeInvocation = true` routes to
 * [evictIfPresent], which this cache rejects: evicting before invocation would race
 * the very load lease the delegate exists to coordinate.
 *
 * [evict] here runs immediately -- transaction-deferred eviction is a separate concern,
 * layered on top by wrapping this cache in [TransactionAwareEvictCache] (as
 * [RedisLeaseCacheManager] does).
 */
class SpringRedisLeaseCache(
    private val name: String,
    private val delegate: LeaseCache,
) : Cache {

    override fun getName(): String = name

    override fun getNativeCache(): Any = delegate

    // The `@Cacheable(sync = true)` path: single-flight load, handled by the delegate.
    // A loader failure comes back as the core's LeaseCacheLoadException and is mapped
    // onto the exception Spring's contract prescribes for get(key, valueLoader).
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? =
        try {
            delegate.get(redisKey(key)) { valueLoader.call() }
        } catch (ex: LeaseCacheLoadException) {
            throw Cache.ValueRetrievalException(key, valueLoader, ex.cause)
        }

    // A value may be written only by the loader that holds the lease, fencing the
    // write on its lease entry (see [LeaseCache]). A tokenless put has no such
    // proof, so allowing it would let anyone overwrite the cache and defeat the lease.
    override fun put(key: Any, value: Any?) {
        throw UnsupportedOperationException("SpringRedisLeaseCache does not support tokenless put(); values are published only by the granted loader")
    }

    override fun evict(key: Any) {
        delegate.evict(redisKey(key))
    }

    // evictIfPresent backs @CacheEvict(beforeInvocation = true) -- evict before the
    // method runs. Rejected fail-fast: an eviction that fires before invocation could
    // race a concurrent load lease acquisition for the same key.
    override fun evictIfPresent(key: Any): Boolean {
        throw UnsupportedOperationException("SpringRedisLeaseCache does not support evictIfPresent(); use @CacheEvict(beforeInvocation = false), the default")
    }

    // The plain value getter is the `@Cacheable(sync = false)` read path, which this
    // cache does not support (that path would then call the unsupported put). Failing
    // fast here surfaces the misconfiguration on the very first call, rather than
    // silently working on hits and blowing up on the first miss.
    override fun get(key: Any): Cache.ValueWrapper? {
        throw UnsupportedOperationException("SpringRedisLeaseCache only works via @Cacheable(sync = true) / get(key, valueLoader); the plain get(key) used by sync = false is unsupported")
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        throw UnsupportedOperationException("SpringRedisLeaseCache only works via @Cacheable(sync = true) / get(key, valueLoader); typed get(key, type) is unsupported")
    }

    override fun clear() {
        throw UnsupportedOperationException("SpringRedisLeaseCache does not support clear(); evict entries individually")
    }

    private fun redisKey(key: Any): String = "$name::$key"
}
