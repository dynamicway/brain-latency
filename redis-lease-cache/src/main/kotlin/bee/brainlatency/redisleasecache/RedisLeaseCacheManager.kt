package bee.brainlatency.redisleasecache

import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RedisLeaseCacheManager(
    private val store: LeaseCacheStore,
    private val codec: LeaseCacheCodec,
    private val leaseTtl: Duration,
    private val valueTtl: Duration,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration = leaseTtl.multipliedBy(2),
) : CacheManager {

    private val caches = ConcurrentHashMap<String, RedisLeaseCache>()

    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) { RedisLeaseCache(it, store, codec, leaseTtl, valueTtl, pollInterval, waitTimeout) }

    override fun getCacheNames(): Collection<String> = caches.keys
}
