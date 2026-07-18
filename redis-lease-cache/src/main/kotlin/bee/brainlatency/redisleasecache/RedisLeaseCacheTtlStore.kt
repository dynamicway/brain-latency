package bee.brainlatency.redisleasecache

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

/**
 * A [LeaseCacheTtlStore] persisted in a single Redis hash (one field per cache name), so a
 * TTL retune on any instance is visible to all of them. Reads are served from a short-lived
 * local cache and only fall through to Redis once an entry is older than [refreshInterval],
 * keeping the hot resolution path in-process; a change still propagates within roughly that
 * window. There is no pub/sub -- propagation is eventual, bounded by [refreshInterval].
 *
 * Each name's TTL is stored as `"<leaseTtl>|<valueTtl>"` in ISO-8601 duration form (e.g.
 * `PT2S|PT10S`). A missing or unparseable field reads back as null, i.e. "unset".
 */
class RedisLeaseCacheTtlStore(
    private val redis: StringRedisTemplate,
    private val refreshInterval: Duration = Duration.ofSeconds(5),
    private val hashKey: String = "brainlatency:lease-cache:ttl",
) : LeaseCacheTtlStore {

    // A null [ttl] caches an "unset" name, so unconfigured names don't re-hit Redis every resolve.
    private class Cached(val ttl: LeaseCacheTtl?, val readAtNanos: Long)

    private val local = ConcurrentHashMap<String, Cached>()

    override fun get(name: String): LeaseCacheTtl? {
        val cached = local[name]
        if (cached != null && System.nanoTime() - cached.readAtNanos < refreshInterval.toNanos()) {
            return cached.ttl
        }
        val ttl = redis.opsForHash<String, String>().get(hashKey, name)?.let(::parse)
        local[name] = Cached(ttl, System.nanoTime())
        return ttl
    }

    override fun put(name: String, ttl: LeaseCacheTtl) {
        redis.opsForHash<String, String>().put(hashKey, name, format(ttl))
        local[name] = Cached(ttl, System.nanoTime())
    }

    private fun format(ttl: LeaseCacheTtl): String = "${ttl.leaseTtl}|${ttl.valueTtl}"

    private fun parse(raw: String): LeaseCacheTtl? {
        val parts = raw.split('|')
        if (parts.size != 2) return null
        return try {
            LeaseCacheTtl(Duration.parse(parts[0]), Duration.parse(parts[1]))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
