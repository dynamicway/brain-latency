package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheEntryCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import java.time.Duration

/**
 * Per-cache-name TTL overrides, e.g. `brainlatency.lease-cache.per-cache.orders.lease-ttl:
 * 2s`. A name absent from [perCache] falls back to the manager's default TTL. Bound
 * separately from lease-ttl/value-ttl via `@ConfigurationProperties` rather than
 * `@param:Value`, because a `Map` of nested objects needs constructor binding, which
 * `@Value` doesn't support.
 */
@ConfigurationProperties(prefix = "brainlatency.lease-cache")
data class LeaseCacheProperties(val perCache: Map<String, LeaseCacheTtl> = emptyMap())

/**
 * Wires a [RedisLeaseCacheManager] as the Spring [CacheManager] on top of an existing
 * [RedisConnectionFactory] -- the byte-framed [RedisTemplate], [LeaseCacheEntryCodec], and
 * [RedisTemplateLeaseCacheStore] the manager needs are assembled here, so an application
 * (or a test) only has to supply a connection factory to get `@Cacheable`/`@CacheEvict`
 * working against [TransactionAwareEvictCache].
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(LeaseCacheProperties::class)
class RedisLeaseCacheConfiguration(
    private val connectionFactory: RedisConnectionFactory,
    // Unset -> null, so the single hardcoded default lives only in LeaseCacheTtl.DEFAULT
    // rather than being duplicated as string defaults here.
    @param:Value("\${brainlatency.lease-cache.lease-ttl:#{null}}") private val leaseTtl: Duration?,
    @param:Value("\${brainlatency.lease-cache.value-ttl:#{null}}") private val valueTtl: Duration?,
    // How long a resolved per-name TTL is served from each instance's local cache before it
    // re-reads the shared store -- the upper bound on how long a runtime retune takes to
    // propagate to an instance that didn't originate it.
    @param:Value("\${brainlatency.lease-cache.ttl-refresh-interval:5s}") private val ttlRefreshInterval: Duration,
    private val leaseCacheProperties: LeaseCacheProperties,
) {

    // Not built with .apply { setConnectionFactory(connectionFactory) ... }: RedisTemplate
    // has its own connectionFactory getter/setter, so inside that lambda the bare name
    // would resolve to the template's own (still-null) property, not this field.
    @Bean
    fun leaseCacheRedisTemplate(): RedisTemplate<String, ByteArray> {
        val template = RedisTemplate<String, ByteArray>()
        template.setConnectionFactory(connectionFactory)
        template.keySerializer = RedisSerializer.string()
        template.valueSerializer = RedisSerializer.byteArray()
        template.afterPropertiesSet()
        return template
    }

    // The Spring Cache contract is untyped, so every bean here is assembled at V = Any;
    // a caller wanting a statically typed cache builds LeaseCache<V> against its own
    // LeaseCacheStore<V> directly (see RedisLeaseCacheManager's note).
    @Bean
    fun leaseCacheCodec(): LeaseCacheEntryCodec<Any> =
        LeaseCacheEntryCodec(RedisSerializerLeaseCacheValueSerializer(RedisSerializer.java(), Any::class.java))

    @Bean
    fun leaseCacheStore(
        leaseCacheRedisTemplate: RedisTemplate<String, ByteArray>,
        leaseCacheCodec: LeaseCacheEntryCodec<Any>,
    ): RedisTemplateLeaseCacheStore<Any> = RedisTemplateLeaseCacheStore(leaseCacheRedisTemplate, leaseCacheCodec)

    // The runtime, cross-instance TTL source of truth: setTtl writes it, every instance
    // reads it (with short local caching) when resolving a name's TTL.
    @Bean
    fun leaseCacheTtlStore(): LeaseCacheTtlStore =
        RedisLeaseCacheTtlStore(StringRedisTemplate(connectionFactory), ttlRefreshInterval)

    @Bean
    fun cacheManager(
        leaseCacheStore: RedisTemplateLeaseCacheStore<Any>,
        leaseCacheTtlStore: LeaseCacheTtlStore,
    ): CacheManager {
        val defaultTtl = LeaseCacheTtl(
            leaseTtl ?: LeaseCacheTtl.DEFAULT.leaseTtl,
            valueTtl ?: LeaseCacheTtl.DEFAULT.valueTtl,
        )
        return RedisLeaseCacheManager(
            leaseCacheStore,
            defaultTtl,
            cacheTtlOverrides = leaseCacheProperties.perCache,
            ttlStore = leaseCacheTtlStore,
        )
    }
}
