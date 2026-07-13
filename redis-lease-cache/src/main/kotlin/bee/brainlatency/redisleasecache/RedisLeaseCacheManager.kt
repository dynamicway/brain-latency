package bee.brainlatency.redisleasecache

import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RedisLeaseCacheManager(
    store: LeaseCacheStore,
    codec: LeaseCacheCodec,
    leaseTtl: Duration,
    valueTtl: Duration,
    pollInterval: Duration = Duration.ofMillis(50),
    waitTimeout: Duration = leaseTtl.multipliedBy(2),
) : CacheManager {

    // Name-agnostic engine shared by every named cache; the per-name adapters below
    // namespace the keys they hand it.
    private val leaseCache = LeaseCache(store, codec, leaseTtl, valueTtl, pollInterval, waitTimeout)

    private val caches = ConcurrentHashMap<String, Cache>()

    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) { TransactionAwareEvictCache(SpringRedisLeaseCache(it, leaseCache)) }

    override fun getCacheNames(): Collection<String> = caches.keys
}
