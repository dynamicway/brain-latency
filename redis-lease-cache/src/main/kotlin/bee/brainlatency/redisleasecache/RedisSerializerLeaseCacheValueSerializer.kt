package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheValueSerializer
import org.springframework.data.redis.serializer.RedisSerializer

/**
 * Bridges a Spring [RedisSerializer] onto the core's [LeaseCacheValueSerializer]
 * strategy, so the codec can be backed by any of Spring's serializers (JDK, JSON, ...)
 * without the core depending on them.
 */
class RedisSerializerLeaseCacheValueSerializer(
    private val delegate: RedisSerializer<Any>,
) : LeaseCacheValueSerializer {

    override fun serialize(value: Any): ByteArray = delegate.serialize(value) ?: ByteArray(0)

    override fun deserialize(bytes: ByteArray): Any? = delegate.deserialize(bytes)
}
