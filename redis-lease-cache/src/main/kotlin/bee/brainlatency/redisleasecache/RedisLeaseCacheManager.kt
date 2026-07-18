package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A cache name's lease/value TTL pair -- how [RedisLeaseCacheManager] takes a per-name TTL override. */
data class LeaseCacheTtl(val leaseTtl: Duration, val valueTtl: Duration) {
    companion object {
        /** [overrides]'s entry for [name], or [default] when the name has none. */
        fun resolve(overrides: Map<String, LeaseCacheTtl>, name: String, default: LeaseCacheTtl): LeaseCacheTtl =
            overrides[name] ?: default
    }
}

/**
 * Every name from [getCache] used to share one [LeaseCache] engine at a single fixed
 * TTL. [LeaseCache] is name-agnostic and stateless beyond its config (see its own doc),
 * so nothing stops each name from getting its own instance instead -- which is what lets
 * [cacheTtlOverrides] give individual names their own lease/value TTL while names absent
 * from it keep sharing the manager's [leaseTtl]/[valueTtl] in effect.
 */
class RedisLeaseCacheManager(
    private val store: LeaseCacheStore<Any>,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration? = null,
    private val cacheTtlOverrides: Map<String, LeaseCacheTtl> = emptyMap(),
) : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache>()

    // Lazy-once per name, same as before -- just resolving that name's TTL first. waitTimeout
    // is passed through only when set explicitly (a fixed value shared by every name, like
    // pollInterval); left unset, LeaseCache's own default derives it from *that* name's leaseTtl.
    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) {
            val ttl = LeaseCacheTtl.resolve(cacheTtlOverrides, it, LeaseCacheTtl(leaseTtl, valueTtl))
            val leaseCache = if (waitTimeout != null) {
                LeaseCache(store, ttl.leaseTtl, ttl.valueTtl, pollInterval, waitTimeout)
            } else {
                LeaseCache(store, ttl.leaseTtl, ttl.valueTtl, pollInterval)
            }
            TransactionAwareEvictCache(it, leaseCache)
        }

    override fun getCacheNames(): Collection<String> = caches.keys
}
