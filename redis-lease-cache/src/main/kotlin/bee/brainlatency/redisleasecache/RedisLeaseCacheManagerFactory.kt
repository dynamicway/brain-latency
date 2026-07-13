package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheConfiguration
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.cache.CacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * The one public door into this library: wires the [RedisTemplate], [LeaseCacheCodec],
 * and [LeaseCacheStore] behind the scenes and hands back only [LeaseCache] (for direct,
 * non-Spring use of the lease protocol) or a Spring [CacheManager] (for `@Cacheable`).
 * Everything in between -- the store port, its Redis-backed implementation, the codec,
 * the value-serializer bridge -- is `internal` and never appears in a caller's
 * signature; a consumer wiring this up only ever names [LeaseCache],
 * [LeaseCacheConfiguration] and this factory.
 *
 * [valueSerializer] is a plain Spring [RedisSerializer], so callers can swap it (JSON,
 * JDK, ...) without needing to know how it's bridged onto the core's serialization
 * strategy.
 */
class RedisLeaseCacheManagerFactory(
    connectionFactory: RedisConnectionFactory,
    valueSerializer: RedisSerializer<Any> = RedisSerializer.java(),
) {

    private val store: LeaseCacheStore = RedisTemplateLeaseCacheStore(
        buildRedisTemplate(connectionFactory),
        LeaseCacheCodec(RedisSerializerLeaseCacheValueSerializer(valueSerializer)),
    )

    /** A standalone lease cache for direct use outside Spring's cache abstraction. */
    fun leaseCache(configuration: LeaseCacheConfiguration): LeaseCache =
        configuration.toLeaseCache(store)

    /**
     * A Spring [CacheManager] whose named caches each run their own [configuration]
     * (from [cacheConfigurations], falling back to [defaultConfiguration]) while
     * sharing this factory's single Redis connection.
     */
    fun cacheManager(
        defaultConfiguration: LeaseCacheConfiguration,
        cacheConfigurations: Map<String, LeaseCacheConfiguration> = emptyMap(),
    ): CacheManager = RedisLeaseCacheManager(store, defaultConfiguration, cacheConfigurations)

    private fun buildRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, ByteArray> =
        RedisTemplate<String, ByteArray>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = RedisSerializer.byteArray()
            afterPropertiesSet()
        }
}
