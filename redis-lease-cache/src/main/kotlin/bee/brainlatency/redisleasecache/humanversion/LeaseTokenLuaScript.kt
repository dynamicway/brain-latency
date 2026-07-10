package bee.brainlatency.redisleasecache.humanversion

import org.springframework.data.redis.core.script.DefaultRedisScript

object LeaseTokenLuaScript {
    private val getLuaScript = """
        local entry = redis.call('GET', KEYS[1])
        if entry then
            return entry
        end
        
        redis.call('SET', KEYS[1], ARGV[1]
    """.trimIndent()
    val GET = DefaultRedisScript<String>(getLuaScript)
}
