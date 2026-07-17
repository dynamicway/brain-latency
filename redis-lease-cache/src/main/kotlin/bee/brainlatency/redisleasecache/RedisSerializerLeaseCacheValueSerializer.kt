package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheValueSerializer
import org.springframework.data.redis.serializer.RedisSerializer

/**
 * Bridges a Spring [RedisSerializer] onto the core's [LeaseCacheValueSerializer]
 * strategy, so the codec can be backed by any of Spring's serializers (JDK, JSON, ...)
 * without the core depending on them.
 *
 * The [type] token is what makes this the trusted boundary: because generics are erased,
 * a plain `as V` in [deserialize] would be unchecked and let a wrong-typed payload slip
 * through to fail elsewhere. [Class.cast] restores a *checked* cast, so a payload that
 * isn't a [V] fails fast right here. For the untyped Spring path ([type] = `Any`) the
 * cast is a harmless no-op; it only bites when a concrete [V] is used directly.
 */
class RedisSerializerLeaseCacheValueSerializer<V : Any>(
    private val delegate: RedisSerializer<Any>,
    private val type: Class<V>,
) : LeaseCacheValueSerializer<V> {

    override fun serialize(value: V): ByteArray = delegate.serialize(value) ?: ByteArray(0)

    override fun deserialize(bytes: ByteArray): V? = type.cast(delegate.deserialize(bytes))
}
