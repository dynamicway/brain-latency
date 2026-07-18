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
        /** The lease/value TTL a cache name falls back to when nothing else configures it. */
        val DEFAULT = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(30))
    }
}

/**
 * Every name from [getCache] used to share one [LeaseCache] engine at a single fixed
 * TTL. [LeaseCache] is name-agnostic and stateless beyond its config (see its own doc),
 * so nothing stops each name from getting its own instance instead -- which is what lets
 * each name resolve its own lease/value TTL while names left unconfigured share [defaultTtl].
 *
 * A name's TTL is resolved on every [getCache] as `[ttlStore] -> [cacheTtlOverrides] ->
 * [defaultTtl]`: the shared [ttlStore] is the runtime, cross-instance source of truth (what
 * [setTtl] writes and every server instance reads), [cacheTtlOverrides] is the static
 * per-name config, and [defaultTtl] the fallback. The built cache is reused while a name's
 * resolved TTL is unchanged and rebuilt the moment it differs -- so a [setTtl] here, or on
 * another instance, is picked up without dropping a single cached entry: those live in the
 * [store] under the name-namespaced keys, untouched by the swapped-out [LeaseCache]. Only
 * operations started after the swap see the new TTL; already-stored values keep whatever
 * expiry they were published with.
 */
class RedisLeaseCacheManager(
    private val store: LeaseCacheStore<Any>,
    private val defaultTtl: LeaseCacheTtl = LeaseCacheTtl.DEFAULT,
    private val pollInterval: Duration = Duration.ofMillis(50),
    private val waitTimeout: Duration? = null,
    private val cacheTtlOverrides: Map<String, LeaseCacheTtl> = emptyMap(),
    private val ttlStore: LeaseCacheTtlStore = InMemoryLeaseCacheTtlStore(),
) : CacheManager {

    private class CachedCache(val ttl: LeaseCacheTtl, val cache: Cache)

    private val caches = ConcurrentHashMap<String, CachedCache>()

    // Resolve the name's current TTL first (may consult the shared store), then reuse the
    // built cache while that TTL holds and rebuild it the moment a retune changes it. The
    // resolve happens outside compute so no store I/O runs under the map's per-key lock.
    override fun getCache(name: String): Cache {
        val ttl = resolveTtl(name)
        return caches.compute(name) { n, existing ->
            if (existing != null && existing.ttl == ttl) existing else CachedCache(ttl, buildCache(n, ttl))
        }!!.cache
    }

    /**
     * Retune [name]'s lease/value TTL at runtime by publishing it to [ttlStore], so this
     * instance and every other resolves to it (others eventually, within the store's refresh
     * window). Already-cached entries survive -- they live in the store, not in the
     * [LeaseCache] the next [getCache] rebuilds.
     */
    fun setTtl(name: String, ttl: LeaseCacheTtl) {
        ttlStore.put(name, ttl)
    }

    private fun resolveTtl(name: String): LeaseCacheTtl =
        ttlStore.get(name) ?: cacheTtlOverrides[name] ?: defaultTtl

    // waitTimeout is passed through only when set explicitly (a fixed value shared by every
    // name, like pollInterval); left unset, LeaseCache's own default derives it from *that*
    // name's leaseTtl.
    private fun buildCache(name: String, ttl: LeaseCacheTtl): Cache {
        val leaseCache = if (waitTimeout != null) {
            LeaseCache(store, ttl.leaseTtl, ttl.valueTtl, pollInterval, waitTimeout)
        } else {
            LeaseCache(store, ttl.leaseTtl, ttl.valueTtl, pollInterval)
        }
        return TransactionAwareEvictCache(name, leaseCache)
    }

    override fun getCacheNames(): Collection<String> = caches.keys
}
