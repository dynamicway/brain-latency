package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RedisLeaseCacheManager(
    store: LeaseCacheStore<Any>,
    leaseTtl: Duration,
    valueTtl: Duration,
    pollInterval: Duration = Duration.ofMillis(50),
    waitTimeout: Duration = leaseTtl.multipliedBy(2),
    storeStallShare: Double = 0.5,
) : CacheManager {

    // Name-agnostic engine shared by every named cache; the per-name adapters below
    // namespace the keys they hand it. The Spring Cache contract is untyped, so the
    // shared engine runs at LeaseCache<Any>; a caller wanting a statically typed cache
    // constructs LeaseCache<V> directly against a LeaseCacheStore<V>, outside this
    // CacheManager (whose getCache(name) can only ever hand back an untyped Cache).
    private val leaseCache = LeaseCache(store, leaseTtl, valueTtl, pollInterval, waitTimeout, storeStallShare)

    private val caches = ConcurrentHashMap<String, Cache>()

    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) { TransactionAwareEvictCache(it, leaseCache) }

    override fun getCacheNames(): Collection<String> = caches.keys
}
