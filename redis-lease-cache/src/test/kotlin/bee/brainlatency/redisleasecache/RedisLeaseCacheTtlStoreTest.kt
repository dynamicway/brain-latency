package bee.brainlatency.redisleasecache

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Exercises [RedisLeaseCacheTtlStore] against a real Redis: the persistence and
 * cross-instance visibility that let a runtime TTL retune reach every server instance.
 */
class RedisLeaseCacheTtlStoreTest : StringSpec({

    val connectionFactory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    val redis = StringRedisTemplate(connectionFactory)
    val hashKey = "brainlatency:lease-cache:ttl:test"

    afterTest {
        redis.delete(hashKey)
    }

    afterSpec {
        connectionFactory.destroy()
    }

    "put then get returns the stored TTL" {
        val store = RedisLeaseCacheTtlStore(redis, hashKey = hashKey)

        store.put("orders", LeaseCacheTtl(Duration.ofSeconds(2), Duration.ofSeconds(10)))

        store.get("orders") shouldBe LeaseCacheTtl(Duration.ofSeconds(2), Duration.ofSeconds(10))
    }

    "an unset name reads back as null" {
        val store = RedisLeaseCacheTtlStore(redis, hashKey = hashKey)

        store.get("never-set") shouldBe null
    }

    "a TTL written by one instance is visible to another reading the same hash" {
        val writer = RedisLeaseCacheTtlStore(redis, hashKey = hashKey)
        // refreshInterval = 0 so every get re-reads Redis, standing in for a change that has
        // propagated past another instance's local-cache window.
        val reader = RedisLeaseCacheTtlStore(redis, refreshInterval = Duration.ZERO, hashKey = hashKey)

        writer.put("orders", LeaseCacheTtl(Duration.ofSeconds(1), Duration.ofMillis(200)))

        reader.get("orders") shouldBe LeaseCacheTtl(Duration.ofSeconds(1), Duration.ofMillis(200))
    }

    "a fresh local cache serves the last read until refreshInterval lapses" {
        val writer = RedisLeaseCacheTtlStore(redis, hashKey = hashKey)
        // a long window: once read, the reader keeps serving that value without re-reading
        val reader = RedisLeaseCacheTtlStore(redis, refreshInterval = Duration.ofMinutes(1), hashKey = hashKey)

        writer.put("orders", LeaseCacheTtl(Duration.ofSeconds(1), Duration.ofSeconds(10)))
        reader.get("orders") shouldBe LeaseCacheTtl(Duration.ofSeconds(1), Duration.ofSeconds(10))

        // writer changes it, but the reader's window has not lapsed, so it still sees the old value
        writer.put("orders", LeaseCacheTtl(Duration.ofSeconds(2), Duration.ofSeconds(20)))
        reader.get("orders") shouldBe LeaseCacheTtl(Duration.ofSeconds(1), Duration.ofSeconds(10))
    }
})
