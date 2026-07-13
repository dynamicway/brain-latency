package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheConfiguration
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Each named cache gets its own [bee.brainlatency.redisleasecache.core.LeaseCache],
 * built from [cacheConfigurations] (falling back to [defaultCacheConfiguration]) --
 * caches can therefore run different [LeaseCacheConfiguration.valueTtl] /
 * [LeaseCacheConfiguration.leaseTtl] policies. Only the [store] (the Redis client and
 * its scripts) is shared, since that's the one genuinely expensive resource; a
 * [bee.brainlatency.redisleasecache.core.LeaseCache] itself is just an immutable config
 * holder, so owning one per name costs nothing.
 *
 * Names listed in [cacheConfigurations] are eagerly created so [getCacheNames] reports
 * them even before first use; any other name is created lazily on first [getCache],
 * using [defaultCacheConfiguration].
 */
class RedisLeaseCacheManager(
    private val store: LeaseCacheStore,
    private val defaultCacheConfiguration: LeaseCacheConfiguration,
    private val cacheConfigurations: Map<String, LeaseCacheConfiguration> = emptyMap(),
) : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache>()

    init {
        cacheConfigurations.keys.forEach(::getCache)
    }

    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) {
            val configuration = cacheConfigurations[it] ?: defaultCacheConfiguration
            TransactionAwareEvictCache(it, configuration.toLeaseCache(store))
        }

    override fun getCacheNames(): Collection<String> = caches.keys
}
