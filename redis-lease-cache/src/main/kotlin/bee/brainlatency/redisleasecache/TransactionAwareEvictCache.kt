package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheOriginException
import org.springframework.cache.Cache
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Callable

/**
 * The Spring [Cache] contract for a [LeaseCache]: a thin adapter that maps the
 * `@Cacheable` / `@CacheEvict` surface onto the lease-token protocol and rejects every
 * mode the protocol can't honour. All the stampede coordination -- the load lease,
 * polling, token-fenced publish -- lives in the [delegate]; this class only owns the
 * cache [name] (namespacing every key as `name::key`) and decides which Spring entry
 * points are legal. The name lives here rather than in the engine, so one [delegate] can
 * back every named cache -- as `RedisLeaseCacheManager` does at `LeaseCache<Any>` -- or a
 * factory can hand each name its own typed `LeaseCache<V>`. Either way this adapter is
 * the untyped Spring edge: the engine is driven at its own [V] and the `Object` values of
 * Spring's `Cache` contract are cast across the boundary here.
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
 * [evict] also defers to the surrounding transaction, when there is one -- the name
 * this class keeps: the entry must outlive the transaction so concurrent readers keep
 * hitting the still-valid value while it's in flight, and eviction must run on every
 * outcome except a certain rollback -- including commit timeout, where the database may
 * have committed. Keying off completion status rather than `afterCommit` covers that
 * unknown-outcome case: the entry is evicted anyway and the next read reloads, rather
 * than potentially serving a value the database no longer holds. Only a certain
 * rollback keeps the entry, since the database is then known unchanged. Unlike Spring's
 * own `TransactionAwareCacheDecorator` (which only ever evicts `afterCommit`), this also
 * evicts on an unknown completion status, for the reason above. Outside a transaction,
 * eviction runs immediately.
 */
class TransactionAwareEvictCache<V : Any>(
    private val name: String,
    private val delegate: LeaseCache<V>,
) : Cache {

    override fun getName(): String = name

    override fun getNativeCache(): Any = delegate

    // The `@Cacheable(sync = true)` path: single-flight load, handled by the delegate.
    // An origin fault comes back as the core's LeaseCacheOriginException -- either the
    // loader threw (ex.cause set) or a waiter timed out with the origin diagnosed as the
    // bottleneck (ex.cause null) -- and both map onto the exception Spring's contract
    // prescribes for get(key, valueLoader): the value couldn't be retrieved. A
    // LeaseCacheStoreException (a cache-tier I/O fault) is deliberately left to propagate
    // as-is, so the origin-vs-cache distinction survives to the caller.
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
        } catch (ex: LeaseCacheOriginException) {
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
