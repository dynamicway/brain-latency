package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheEntryCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import bee.brainlatency.redisleasecache.core.LeaseStoreException
import bee.brainlatency.redisleasecache.core.LeaseToken
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The Spring Data Redis implementation of [LeaseCacheStore]: runs the Lua in
 * [RedisLeaseCacheScripts] through a [RedisTemplate] and owns the [codec], marshalling
 * both the raw arguments (keys, TTLs as millis bytes) and the byte framing (leases,
 * values, the null marker). The core never touches KEYS/ARGV ordering or byte-encoded
 * durations -- that happens only here, at the Redis I/O boundary.
 *
 * Anything this class's machinery throws -- a connection failure out of the template
 * (after the client's own retries), a codec that can't decode an entry -- comes out as
 * [LeaseStoreException], so the core never sees a Spring or Lettuce type.
 */
class RedisTemplateLeaseCacheStore<V : Any>(
    private val redisTemplate: RedisTemplate<String, ByteArray>,
    private val codec: LeaseCacheEntryCodec<V>,
) : LeaseCacheStore<V> {

    override fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry<V> = redisAccess(key) {
        val raw = redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            codec.encodeLease(leaseToken),
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")
        codec.decode(raw)
    }

    override fun publish(key: String, leaseToken: LeaseToken, value: V?, valueTtl: Duration): Boolean = redisAccess(key) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            codec.encodeLease(leaseToken),
            codec.encodeValue(value),
            valueTtl.toArgvMillis(),
        ) == 1L
    }

    override fun release(key: String, leaseToken: LeaseToken): Unit = redisAccess(key) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), codec.encodeLease(leaseToken))
    }

    override fun evict(key: String): Boolean = redisAccess(key) {
        redisTemplate.delete(key)
    }

    // Every Redis access runs through here so any failure -- connection, script, codec --
    // leaves as LeaseStoreException instead of a Spring/Lettuce type.
    private inline fun <T> redisAccess(key: String, op: () -> T): T =
        try {
            op()
        } catch (ex: Exception) {
            throw LeaseStoreException(key, ex)
        }

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
