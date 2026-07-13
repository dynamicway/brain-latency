package bee.brainlatency.redisleasecache.core

/**
 * The value-serialization strategy behind [LeaseCacheCodec]: how a cached value becomes
 * payload bytes and back, decoupling the core from any concrete serializer (Spring's
 * `RedisSerializer`, JSON, ...). Nulls never reach it -- the codec frames a negatively
 * cached null as a bare tag with no payload -- so implementations deal only in real
 * values.
 */
interface LeaseCacheValueSerializer {

    fun serialize(value: Any): ByteArray

    fun deserialize(bytes: ByteArray): Any?
}
