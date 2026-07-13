package bee.brainlatency.redisleasecache

import org.springframework.cache.Cache
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Callable

/**
 * The Spring [Cache] contract for a [LeaseTokenCache]: a thin adapter that maps the
 * `@Cacheable` / `@CacheEvict` surface onto the lease-token protocol and rejects every
 * mode the protocol can't honour. All the stampede coordination -- the load lease,
 * polling, token-fenced publish -- lives in the [delegate]; this class only owns the
 * cache [name] (namespacing every key as `name::key`), decides which Spring entry
 * points are legal, and defers eviction to transaction completion. One [delegate] is
 * shared across all named caches, since the name lives here rather than in the engine.
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
 * Inside a transaction, [evict] is deferred to after completion and runs unless the
 * transaction rolled back. Keying off completion status rather than `afterCommit`
 * covers the commit-timeout case: the outcome is then *unknown* -- the database may
 * well have committed -- so the entry is evicted anyway and the next read reloads,
 * rather than potentially serving a value the database no longer holds. Only a
 * certain rollback keeps the entry, since the database is then known unchanged.
 */
class SpringRedisLeaseTokenCache(
    private val name: String,
    private val delegate: LeaseTokenCache,
) : Cache {

    override fun getName(): String = name

    override fun getNativeCache(): Any = delegate

    // The `@Cacheable(sync = true)` path: single-flight load, handled by the delegate.
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? = delegate.get(redisKey(key), valueLoader)

    // A value may be written only by the loader that holds the lease, fencing the
    // write on its lease entry (see [LeaseTokenCache]). A tokenless put has no such
    // proof, so allowing it would let anyone overwrite the cache and defeat the lease.
    override fun put(key: Any, value: Any?) {
        throw UnsupportedOperationException("SpringRedisLeaseTokenCache does not support tokenless put(); values are published only by the granted loader")
    }

    // Deferred inside a transaction: the entry must outlive the transaction so
    // concurrent readers keep hitting the still-valid value, and must go on every
    // outcome except a certain rollback -- including commit timeout, where the
    // database may have committed (see the class doc).
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
        throw UnsupportedOperationException("SpringRedisLeaseTokenCache does not support evictIfPresent(); use @CacheEvict(beforeInvocation = false), the default")
    }

    // The plain value getter is the `@Cacheable(sync = false)` read path, which this
    // cache does not support (that path would then call the unsupported put). Failing
    // fast here surfaces the misconfiguration on the very first call, rather than
    // silently working on hits and blowing up on the first miss.
    override fun get(key: Any): Cache.ValueWrapper? {
        throw UnsupportedOperationException("SpringRedisLeaseTokenCache only works via @Cacheable(sync = true) / get(key, valueLoader); the plain get(key) used by sync = false is unsupported")
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        throw UnsupportedOperationException("SpringRedisLeaseTokenCache only works via @Cacheable(sync = true) / get(key, valueLoader); typed get(key, type) is unsupported")
    }

    override fun clear() {
        throw UnsupportedOperationException("SpringRedisLeaseTokenCache does not support clear(); evict entries individually")
    }

    private fun redisKey(key: Any): String = "$name::$key"
}
