package bee.brainlatency.redisleasecache.core

/**
 * The value-serialization strategy behind [LeaseCacheCodec]: how a cached value of type
 * [V] becomes payload bytes and back, decoupling the core from any concrete serializer
 * (Spring's `RedisSerializer`, JSON, ...). Nulls never reach it -- the codec frames a
 * negatively cached null as a bare tag with no payload -- so [serialize] deals only in
 * real, non-null values, which is why [V] is bounded `Any`.
 *
 * This is the one boundary where static typing bottoms out into a runtime assertion:
 * bytes carry no type, so [deserialize] is where "these bytes are a [V]" is vouched for.
 * An implementation that holds a `Class<V>` can make that a *checked* cast and fail fast,
 * rather than an unchecked one that surfaces later at some unrelated use site.
 */
interface LeaseCacheSerializer<V : Any> {

    fun serialize(value: V): ByteArray

    fun deserialize(bytes: ByteArray): V?
}
