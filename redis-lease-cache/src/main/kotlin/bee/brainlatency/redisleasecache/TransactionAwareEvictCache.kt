package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.OriginLoadException
import org.springframework.cache.Cache
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Callable

/**
 * The Spring [Cache] contract for a [LeaseCache]: a thin adapter that maps
 * `@Cacheable`/`@CacheEvict` onto the lease-token protocol and rejects every mode the
 * protocol can't honour. All stampede coordination lives in the [delegate]; this class
 * only owns the cache [name] (namespacing keys as `name::key`), so one [delegate] can
 * back every named cache -- as `RedisLeaseCacheManager` does at `LeaseCache<Any>` -- or
 * a factory can hand each name its own typed `LeaseCache<V>`.
 *
 * Only `@Cacheable(sync = true)` is supported -- [get] with a `valueLoader`. The
 * `sync = false` path (plain [get], tokenless [put]) and typed [get]/[clear] are
 * rejected fail-fast, so misuse breaks on the first call instead of silently working
 * until the first miss. `@CacheEvict(beforeInvocation = true)`, which routes to
 * [evictIfPresent], is rejected the same way.
 *
 * [evict] defers to the surrounding transaction when there is one: the entry must
 * outlive the transaction so concurrent readers keep hitting it while it's in flight,
 * and it's evicted on every outcome except a confirmed rollback -- including an unknown
 * completion status, where the database may have committed. That differs from Spring's
 * own `TransactionAwareCacheDecorator`, which only ever evicts `afterCommit`. Outside a
 * transaction, eviction runs immediately.
 */
class TransactionAwareEvictCache<V : Any>(
    private val name: String,
    private val delegate: LeaseCache<V>,
) : Cache {

    override fun getName(): String = name

    override fun getNativeCache(): Any = delegate

    // The `@Cacheable(sync = true)` path: single-flight load, handled by the delegate.
    // A loader failure comes back as the core's OriginLoadException and is mapped onto
    // the exception Spring's contract prescribes for get(key, valueLoader). The core's
    // other failures (store outage, wait timeout) are not loader failures, so that
    // contract does not apply to them and they propagate as themselves.
    //
    // This is the Object boundary: Spring's Cache contract is untyped, so the caller's T
    // and the engine's V are bridged by two unchecked casts here -- the loader's result
    // into V on the way in, the stored value back into T on the way out. Both are
    // inherent to Spring's `<T> T get(..)` signature and live at this edge, not in the
    // typed core. They are safe when T == V (no projection); a cache that stores a
    // projection of the loaded type would map here instead of casting.
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? =
        try {
            @Suppress("UNCHECKED_CAST")
            delegate.get(redisKey(key)) {
                @Suppress("UNCHECKED_CAST")
                valueLoader.call() as V?
            } as T?
        } catch (ex: OriginLoadException) {
            throw Cache.ValueRetrievalException(key, valueLoader, ex.cause)
        }

    // A value may be written only by the loader that holds the lease, fencing the
    // write on its lease entry (see [LeaseCache]). A tokenless put has no such
    // proof, so allowing it would let anyone overwrite the cache and defeat the lease.
    override fun put(key: Any, value: Any?) {
        throw UnsupportedOperationException("TransactionAwareEvictCache does not support tokenless put(); values are published only by the granted loader")
    }

    override fun evict(key: Any) {
        val redisKey = redisKey(key)
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delegate.evict(redisKey)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    delegate.evict(redisKey)
                }
            }
        })
    }

    // evictIfPresent backs @CacheEvict(beforeInvocation = true) -- evict before the
    // method runs. Rejected fail-fast: an eviction that fires before invocation could
    // race a concurrent load lease acquisition for the same key.
    override fun evictIfPresent(key: Any): Boolean {
        throw UnsupportedOperationException("TransactionAwareEvictCache does not support evictIfPresent(); use @CacheEvict(beforeInvocation = false), the default")
    }

    // The plain value getter is the `@Cacheable(sync = false)` read path, which this
    // cache does not support (that path would then call the unsupported put). Failing
    // fast here surfaces the misconfiguration on the very first call, rather than
    // silently working on hits and blowing up on the first miss.
    override fun get(key: Any): Cache.ValueWrapper? {
        throw UnsupportedOperationException("TransactionAwareEvictCache only works via @Cacheable(sync = true) / get(key, valueLoader); the plain get(key) used by sync = false is unsupported")
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        throw UnsupportedOperationException("TransactionAwareEvictCache only works via @Cacheable(sync = true) / get(key, valueLoader); typed get(key, type) is unsupported")
    }

    override fun clear() {
        throw UnsupportedOperationException("TransactionAwareEvictCache does not support clear(); evict entries individually")
    }

    private fun redisKey(key: Any): String = "$name::$key"
}
