package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The Spring Data Redis implementation of the core's [LeaseCacheStore] port: runs the
 * Lua in [RedisLeaseCacheScripts] through a [RedisTemplate] and marshals the arguments
 * (keys, framed entries, TTLs as millis bytes), so the core deals in domain terms -- an
 * entry, a lease, a TTL -- and never touches KEYS/ARGV ordering or byte-encoded
 * durations.
 */
class RedisTemplateLeaseCacheStore(private val redisTemplate: RedisTemplate<String, ByteArray>) : LeaseCacheStore {

    override fun getOrAcquire(key: String, leaseEntry: ByteArray, leaseTtl: Duration): ByteArray =
        redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            leaseEntry,
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")

    override fun publish(key: String, leaseEntry: ByteArray, payload: ByteArray, valueTtl: Duration) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            leaseEntry,
            payload,
            valueTtl.toArgvMillis(),
        )
    }

    override fun release(key: String, leaseEntry: ByteArray) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), leaseEntry)
    }

    override fun evict(key: String): Boolean = redisTemplate.delete(key)

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
