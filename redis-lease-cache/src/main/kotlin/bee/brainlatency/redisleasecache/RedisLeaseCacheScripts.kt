package bee.brainlatency.redisleasecache

import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * The atomic Redis-side operations behind [RedisTemplateLeaseCacheStore], kept in one
 * place so the store adapter and the Lua live apart. Both are binary-safe: KEYS/ARGV
 * are byte strings, so the framed entries pass through unchanged.
 */
internal object RedisLeaseCacheScripts {

    // Return the current entry if present, otherwise write our load lease and return
    // it -- one command, so the get and the acquire never interleave with another client.
    private val getOrAcquire = """
        local v = redis.call('get', KEYS[1])
        if v then return v end
        redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
        return ARGV[1]
    """.trimIndent()
    val GET_OR_ACQUIRE: DefaultRedisScript<ByteArray> = DefaultRedisScript(getOrAcquire, ByteArray::class.java)

    // Token-fenced publish: overwrite the key with the value payload only if it still
    // holds our lease entry. Returns 1 if the write landed, 0 if the lease was lost
    // (expired / taken over), in which case the newer entry is left intact.
    private val publish = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
        else
            return 0
        end
    """.trimIndent()
    val PUBLISH: DefaultRedisScript<Long> = DefaultRedisScript(publish, Long::class.java)

    // Token-fenced release (the classic Redlock unlock shape): delete the key only
    // if it still holds our lease entry. Returns 1 if released, 0 if the lease was
    // already lost (expired / taken over), leaving the newer entry intact.
    private val release = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
    """.trimIndent()
    val RELEASE: DefaultRedisScript<Long> = DefaultRedisScript(release, Long::class.java)
}
