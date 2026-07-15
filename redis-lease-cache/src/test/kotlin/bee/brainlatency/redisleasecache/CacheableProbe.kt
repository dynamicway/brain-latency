package bee.brainlatency.redisleasecache

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal `@Cacheable`/`@CacheEvict` surface for exercising the AOP contract that
 * [TransactionAwareEvictCache] enforces through real Spring caching, not a direct call
 * to the `Cache` interface: only `sync = true` reads and `beforeInvocation = false`
 * (the default) evictions are supported.
 */
@Component
class CacheableProbe {

    val loads = AtomicInteger(0)

    @Cacheable(cacheNames = ["probe"], sync = true)
    fun loadSynced(key: String): String = "loaded-${loads.incrementAndGet()}"

    @Cacheable(cacheNames = ["probe"])
    fun loadUnsynced(key: String): String = "loaded-${loads.incrementAndGet()}"

    @CacheEvict(cacheNames = ["probe"])
    fun evict(key: String) = Unit

    @CacheEvict(cacheNames = ["probe"], beforeInvocation = true)
    fun evictBeforeInvocation(key: String) = Unit
}
