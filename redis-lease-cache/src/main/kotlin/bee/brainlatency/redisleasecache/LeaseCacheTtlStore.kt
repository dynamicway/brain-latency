package bee.brainlatency.redisleasecache

import java.util.concurrent.ConcurrentHashMap

/**
 * The shared source of truth for per-cache-name TTL, so a [RedisLeaseCacheManager.setTtl]
 * on one server instance is seen by the others. [get] is what each instance consults when
 * resolving a name's TTL; [put] is how a retune is published to every instance.
 *
 * A multi-instance deployment backs this with [RedisLeaseCacheTtlStore] (Redis-persisted,
 * read with short local caching); the default [InMemoryLeaseCacheTtlStore] keeps a single
 * process self-contained.
 */
interface LeaseCacheTtlStore {
    /** [name]'s TTL, or null if none is set -- the caller then falls back to its own default. */
    fun get(name: String): LeaseCacheTtl?

    /** Publish [name]'s TTL so every instance reading this store resolves to it. */
    fun put(name: String, ttl: LeaseCacheTtl)
}

/** A single-process [LeaseCacheTtlStore]; the manager's default when no shared store is wired. */
class InMemoryLeaseCacheTtlStore : LeaseCacheTtlStore {
    private val ttls = ConcurrentHashMap<String, LeaseCacheTtl>()

    override fun get(name: String): LeaseCacheTtl? = ttls[name]

    override fun put(name: String, ttl: LeaseCacheTtl) {
        ttls[name] = ttl
    }
}
