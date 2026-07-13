package bee.brainlatency.redisleasecache

import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.util.UUID

/**
 * The domain-level facade over Redis behind [LeaseCache]: runs the Lua in
 * [RedisLeaseCacheScripts] and owns the [LeaseCacheCodec], so it both marshals the raw
 * arguments (keys, TTLs as millis bytes) *and* the byte framing (leases, values, the
 * null marker). The cache above deals only in domain terms -- mint a lease, get-or-
 * acquire an entry, publish a value, release, evict -- and never touches KEYS/ARGV
 * ordering, byte-encoded durations, entry framing, or raw lease bytes: [LeaseToken]
 * unwraps to [ByteArray] only right here, at the Redis I/O boundary.
 */
class LeaseCacheStore(
    private val redisTemplate: RedisTemplate<String, ByteArray>,
    private val codec: LeaseCacheCodec,
) {

    /** A fresh, uniquely-identified lease token to attempt acquisition with. */
    fun newLease(): LeaseToken =
        LeaseToken(codec.leaseEntry(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)))

    /** Atomically return the decoded entry at [key], or write [leaseToken] (living [leaseTtl]) and return it. */
    fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry {
        val raw = redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            leaseToken.toBytes(),
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")
        return codec.decode(raw)
    }

    /** Frame and write [value] at [key] (living [valueTtl]) only if it still holds [leaseToken]. */
    fun publish(key: String, leaseToken: LeaseToken, value: Any?, valueTtl: Duration) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            leaseToken.toBytes(),
            codec.valueEntry(value),
            valueTtl.toArgvMillis(),
        )
    }

    /** Delete [key] only if it still holds [leaseToken], releasing an in-flight lease. */
    fun release(key: String, leaseToken: LeaseToken) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), leaseToken.toBytes())
    }

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean = redisTemplate.delete(key)

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
