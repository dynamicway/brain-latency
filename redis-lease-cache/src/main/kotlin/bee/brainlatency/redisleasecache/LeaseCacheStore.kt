package bee.brainlatency.redisleasecache

import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The Redis I/O behind [LeaseTokenCache]: runs the Lua in [RedisLeaseCacheScripts]
 * and marshals the arguments (keys, framed entries, TTLs as millis bytes), so the
 * cache deals in domain terms -- an entry, a lease, a TTL -- and never touches
 * KEYS/ARGV ordering or byte-encoded durations.
 */
class LeaseCacheStore(private val redisTemplate: RedisTemplate<String, ByteArray>) {

    /** Atomically return the entry at [key], or write [leaseEntry] (living [leaseTtl]) and return it. */
    fun getOrAcquire(key: String, leaseEntry: ByteArray, leaseTtl: Duration): ByteArray =
        redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            leaseEntry,
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")

    /** Write [payload] at [key] (living [valueTtl]) only if it still holds [leaseEntry]. */
    fun publish(key: String, leaseEntry: ByteArray, payload: ByteArray, valueTtl: Duration) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            leaseEntry,
            payload,
            valueTtl.toArgvMillis(),
        )
    }

    /** Delete [key] only if it still holds [leaseEntry], releasing an in-flight lease. */
    fun release(key: String, leaseEntry: ByteArray) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), leaseEntry)
    }

    /** Remove the entry at [key] -- a value or an in-flight lease alike. Returns whether it existed. */
    fun evict(key: String): Boolean = redisTemplate.delete(key)

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
