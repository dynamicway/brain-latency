package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import bee.brainlatency.redisleasecache.core.LeaseToken
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The Spring Data Redis implementation of the core's [LeaseCacheStore] port: runs the
 * Lua in [RedisLeaseCacheScripts] through a [RedisTemplate] and owns the [codec], so it
 * marshals both the raw arguments (keys, TTLs as millis bytes) *and* the byte framing
 * (leases, values, the null marker). The core deals only in domain terms -- get-or-acquire
 * an entry, publish a value, release, evict -- and never touches KEYS/ARGV ordering,
 * byte-encoded durations, entry framing, or raw lease bytes: [LeaseToken] unwraps to
 * [ByteArray] only right here, at the Redis I/O boundary.
 */
class RedisTemplateLeaseCacheStore<V : Any>(
    private val redisTemplate: RedisTemplate<String, ByteArray>,
    private val codec: LeaseCacheCodec<V>,
) : LeaseCacheStore<V> {

    override fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry<V> {
        val raw = redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            leaseToken.framedEntry(),
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")
        return codec.decode(raw)
    }

    override fun publish(key: String, leaseToken: LeaseToken, value: V?, valueTtl: Duration) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            leaseToken.framedEntry(),
            codec.valueEntry(value),
            valueTtl.toArgvMillis(),
        )
    }

    override fun release(key: String, leaseToken: LeaseToken) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), leaseToken.framedEntry())
    }

    override fun evict(key: String): Boolean = redisTemplate.delete(key)

    // A held lease is stored -- and token-fenced -- as its framed entry, so the bytes on
    // the wire carry the tag decode reads to tell a lease from a value, and the CAS in
    // PUBLISH/RELEASE compares like against like.
    private fun LeaseToken.framedEntry(): ByteArray = codec.leaseEntry(toBytes())

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
