package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import bee.brainlatency.redisleasecache.core.LeaseCacheStore
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.util.UUID

/**
 * The Spring Data Redis implementation of the core's [LeaseCacheStore] port: runs the
 * Lua in [RedisLeaseCacheScripts] through a [RedisTemplate] and owns the [codec], so it
 * marshals both the raw arguments (keys, TTLs as millis bytes) *and* the byte framing
 * (leases, values, the null marker). The core deals only in domain terms -- mint a
 * lease, get-or-acquire an entry, publish a value, release, evict -- and never touches
 * KEYS/ARGV ordering, byte-encoded durations, or the entry framing.
 */
class RedisTemplateLeaseCacheStore(
    private val redisTemplate: RedisTemplate<String, ByteArray>,
    private val codec: LeaseCacheCodec,
) : LeaseCacheStore {

    override fun newLease(): ByteArray = codec.leaseEntry(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))

    override fun getOrAcquire(key: String, leaseToken: ByteArray, leaseTtl: Duration): LeaseCacheEntry {
        val raw = redisTemplate.execute(
            RedisLeaseCacheScripts.GET_OR_ACQUIRE,
            listOf(key),
            leaseToken,
            leaseTtl.toArgvMillis(),
        ) ?: error("GET_OR_ACQUIRE returned null")
        return codec.decode(raw)
    }

    override fun publish(key: String, leaseToken: ByteArray, value: Any?, valueTtl: Duration) {
        redisTemplate.execute(
            RedisLeaseCacheScripts.PUBLISH,
            listOf(key),
            leaseToken,
            codec.valueEntry(value),
            valueTtl.toArgvMillis(),
        )
    }

    override fun release(key: String, leaseToken: ByteArray) {
        redisTemplate.execute(RedisLeaseCacheScripts.RELEASE, listOf(key), leaseToken)
    }

    override fun evict(key: String): Boolean = redisTemplate.delete(key)

    // Lua PX arguments travel as decimal-string bytes, like every other ARGV.
    private fun Duration.toArgvMillis(): ByteArray = toMillis().toString().toByteArray()
}
