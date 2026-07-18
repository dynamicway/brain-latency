package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Every name from [getCache] used to share one [LeaseCache] engine at a single fixed
 * [defaultValueTtl]. [LeaseCache] is name-agnostic and stateless beyond its config (see
 * its own doc), so nothing stops each name from getting its own instance instead --
 * which is what lets [valueTtlOverrides] give individual names their own expiry while
 * names absent from it keep sharing [defaultValueTtl]. leaseTtl is not part of this: it
 * only bounds the stampede-protection window, not how long a value lives, so every name
 * uses [LeaseCache]'s own internal default.
 */
class RedisLeaseCacheManager(
    private val store: LeaseCacheStore<Any>,
    private val defaultValueTtl: Duration = Duration.ofSeconds(30),
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration? = null,
    private val valueTtlOverrides: Map<String, Duration> = emptyMap(),
) : CacheManager {

    private val caches = ConcurrentHashMap<String, Cache>()

    override fun getCache(name: String): Cache =
        caches.computeIfAbsent(name) { buildCache(it, valueTtlOverrides[it] ?: defaultValueTtl) }

    // waitTimeout is passed through only when set explicitly (a fixed value shared by every
    // name, like pollInterval); left unset, LeaseCache's own default derives it from its own
    // internal leaseTtl.
    private fun buildCache(name: String, valueTtl: Duration): Cache {
        val leaseCache = if (waitTimeout != null) {
            LeaseCache(store, valueTtl, pollInterval = pollInterval, waitTimeout = waitTimeout)
        } else {
            LeaseCache(store, valueTtl, pollInterval = pollInterval)
        }
        return TransactionAwareEvictCache(name, leaseCache)
    }

    override fun getCacheNames(): Collection<String> = caches.keys
}
