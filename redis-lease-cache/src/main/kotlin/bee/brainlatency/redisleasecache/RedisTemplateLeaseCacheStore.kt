package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheEntryCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import bee.brainlatency.redisleasecache.core.LeaseStoreException
import bee.brainlatency.redisleasecache.core.LeaseToken
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The Spring Data Redis implementation of the core's [LeaseCacheStore] port: runs the
 * Lua in [RedisLeaseCacheScripts] through a [RedisTemplate] and owns the [codec], so it
 * marshals both the raw arguments (keys, TTLs as millis bytes) *and* the byte framing
 * (leases, values, the null marker). The core deals only in domain terms -- get-or-acquire
 * an entry, publish a value, release, evict -- and never touches KEYS/ARGV ordering or
 * byte-encoded durations, which are marshalled only right here, at the Redis I/O
 * boundary. Entry framing it delegates to the [codec], passing [LeaseToken] whole.
 *
 * Failures follow the port's contract: anything this adapter's machinery throws --
 * a connection failure out of the template (after the client's own retries), a codec
 * that can't decode an entry -- comes out as [LeaseStoreException], so the core never
 * sees a Spring or Lettuce type.
 */
class RedisTemplateLeaseCacheStore<V : Any>(
    private val redisTemplate: RedisTemplate<String, ByteArray>,
    private val codec: LeaseCacheEntryCodec<V>,
) : LeaseCacheStore<V> {

    override fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry<V> = storeFailureIsOurs(key) {
        val raw = redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            codec.encodeLease(leaseToken),
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")
        codec.decode(raw)
    }

    override fun publish(key: String, leaseToken: LeaseToken, value: V?, valueTtl: Duration): Boolean = storeFailureIsOurs(key) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            codec.encodeLease(leaseToken),
            codec.encodeValue(value),
            valueTtl.toArgvMillis(),
        ) == 1L
    }

    override fun release(key: String, leaseToken: LeaseToken): Unit = storeFailureIsOurs(key) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), codec.encodeLease(leaseToken))
    }

    override fun evict(key: String): Boolean = storeFailureIsOurs(key) {
        redisTemplate.delete(key)
    }

    // The port's failure contract: whatever breaks inside this adapter is by definition
    // a store failure, so it leaves as the domain's LeaseStoreException.
    private inline fun <T> storeFailureIsOurs(key: String, op: () -> T): T =
        try {
            op()
        } catch (ex: Exception) {
            throw LeaseStoreException(key, ex)
        }

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
