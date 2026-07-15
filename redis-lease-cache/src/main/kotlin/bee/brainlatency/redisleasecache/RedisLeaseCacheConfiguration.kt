package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import java.time.Duration

/**
 * Wires a [RedisLeaseCacheManager] as the Spring [CacheManager] on top of an existing
 * [RedisConnectionFactory] -- the byte-framed [RedisTemplate], [LeaseCacheCodec], and
 * [RedisTemplateLeaseCacheStore] the manager needs are assembled here, so an application
 * (or a test) only has to supply a connection factory to get `@Cacheable`/`@CacheEvict`
 * working against [TransactionAwareEvictCache].
 */
@Configuration
@EnableCaching
class RedisLeaseCacheConfiguration(
    private val connectionFactory: RedisConnectionFactory,
    @param:Value("\${brainlatency.lease-cache.lease-ttl:5s}") private val leaseTtl: Duration,
    @param:Value("\${brainlatency.lease-cache.value-ttl:30s}") private val valueTtl: Duration,
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

    @Bean
    fun leaseCacheCodec(): LeaseCacheCodec<Any> =
        LeaseCacheCodec(RedisSerializerLeaseCacheSerializer(RedisSerializer.java(), Any::class.java))

    @Bean
    fun leaseCacheStore(
        leaseCacheRedisTemplate: RedisTemplate<String, ByteArray>,
        leaseCacheCodec: LeaseCacheCodec<Any>,
    ): RedisTemplateLeaseCacheStore<Any> = RedisTemplateLeaseCacheStore(leaseCacheRedisTemplate, leaseCacheCodec)

    @Bean
    fun cacheManager(leaseCacheStore: RedisTemplateLeaseCacheStore<Any>): CacheManager =
        RedisLeaseCacheManager(leaseCacheStore, leaseTtl, valueTtl)
}
