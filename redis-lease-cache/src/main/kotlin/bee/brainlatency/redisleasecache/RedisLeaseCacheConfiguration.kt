package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheEntryCodec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import java.time.Duration

/**
 * [valueTtl] is the default a cache name lives for; [perCache] overrides it by name (e.g.
 * `brainlatency.lease-cache.per-cache.orders: 2s`) for names that need a different expiry.
 * Bound via `@ConfigurationProperties` rather than `@param:Value` because a `Map` needs
 * constructor binding, which `@Value` doesn't support.
 */
@ConfigurationProperties(prefix = "brainlatency.lease-cache")
data class LeaseCacheProperties(
    val valueTtl: Duration = Duration.ofSeconds(30),
    val perCache: Map<String, Duration> = emptyMap(),
)

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
    private val leaseCacheProperties: LeaseCacheProperties,
) {

    // Not built with .apply { setConnectionFactory(connectionFactory) ... }: RedisTemplate
    // has its own connectionFactory getter/setter, so inside that lambda the bare name
    // would resolve to the template's own (still-null) property, not this field.
    @Bean
    fun leaseCacheRedisTemplate(): RedisTemplate<String, ByteArray> {
        val template = RedisTemplate<String, ByteArray>()
        template.connectionFactory = connectionFactory
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

    @Bean
    fun cacheManager(leaseCacheStore: RedisTemplateLeaseCacheStore<Any>): CacheManager =
        RedisLeaseCacheManager(
            leaseCacheStore,
            defaultValueTtl = leaseCacheProperties.valueTtl,
            valueTtlOverrides = leaseCacheProperties.perCache,
        )
}
