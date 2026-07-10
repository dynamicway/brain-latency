package bee.brainlatency.redisleasecache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

class RedisLeaseCacheTest : StringSpec({

    val connectionFactory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    val redisTemplate = RedisTemplate<String, ByteArray>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer = StringRedisSerializer()
        valueSerializer = RedisSerializer.byteArray()
        afterPropertiesSet()
    }
    val store = LeaseCacheStore(redisTemplate)
    val codec = LeaseCacheCodec(RedisSerializer.java())
    val cache = RedisLeaseCache("test-lease", store, codec, Duration.ofSeconds(5), Duration.ofSeconds(5))

    afterTest {
        listOf("test-lease", "short-value").forEach { name ->
            redisTemplate.delete(redisTemplate.keys("$name::*") ?: emptySet())
        }
    }

    afterSpec {
        connectionFactory.destroy()
    }

    "tokenless put is unsupported because an unfenced write would defeat the lease" {
        shouldThrow<UnsupportedOperationException> {
            cache.put("resource-4", "direct-value")
        }
    }

    "plain get(key) is unsupported so sync = false misuse fails fast" {
        shouldThrow<UnsupportedOperationException> {
            cache.get("resource-4")
        }
    }

    "a zombie loader whose lease was taken over cannot overwrite the newer entry" {
        val zombieLoader = Callable {
            // simulate our lease being lost and a new holder publishing a fresh value
            cache.evict("resource-6")
            cache.get("resource-6", Callable { "fresh" })
            "stale"
        }

        // our caller still gets the value we loaded...
        cache.get("resource-6", zombieLoader) shouldBe "stale"
        // ...but the fenced publish did NOT clobber the newer holder's value
        cache.get("resource-6", Callable { "reloaded" }) shouldBe "fresh"
    }

    "a cached value expires after valueTtl so the next call reloads" {
        val shortValueCache = RedisLeaseCache("short-value", store, codec, Duration.ofSeconds(5), Duration.ofMillis(300))
        val loads = AtomicInteger(0)
        val loader = Callable { "loaded-${loads.incrementAndGet()}" }

        shortValueCache.get("resource-5", loader) shouldBe "loaded-1"
        Thread.sleep(500)
        shortValueCache.get("resource-5", loader) shouldBe "loaded-2"
        loads.get() shouldBe 2
    }

    "get(valueLoader) loads once on miss then serves the cached value without reloading" {
        val loads = AtomicInteger(0)
        val loader = Callable { "loaded-${loads.incrementAndGet()}" }

        cache.get("resource-7", loader) shouldBe "loaded-1"
        cache.get("resource-7", loader) shouldBe "loaded-1"
        loads.get() shouldBe 1
    }

    "get(valueLoader) caches an arbitrary object type via the injected serializer" {
        val loaded = cache.get("resource-10", Callable { Point(3, 4) })
        loaded shouldBe Point(3, 4)

        // served from cache and deserialized back to the same type, loader not re-run
        cache.get("resource-10", Callable { Point(9, 9) }) shouldBe Point(3, 4)
    }

    "get(valueLoader) negatively caches a null result so the loader is not re-invoked" {
        val loads = AtomicInteger(0)
        @Suppress("UNCHECKED_CAST")
        val nullLoader = Callable { loads.incrementAndGet(); null } as Callable<String>

        cache.get("resource-8", nullLoader).shouldBeNull()
        cache.get("resource-8", nullLoader).shouldBeNull()
        loads.get() shouldBe 1
    }

    "get(valueLoader) does not wait while another loader holds the lease (polling is a TODO)" {
        // simulate another loader holding the load lease (a T-tagged framed entry)
        val foreignLease = byteArrayOf('T'.code.toByte()) + "someone-else".toByteArray()
        redisTemplate.opsForValue().set("test-lease::resource-9", foreignLease, Duration.ofSeconds(5))

        shouldThrow<UnsupportedOperationException> {
            cache.get("resource-9", Callable { "value" })
        }
    }
})

private data class Point(val x: Int, val y: Int) : java.io.Serializable
