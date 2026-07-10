package bee.brainlatency.redisleasecache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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

    "evictIfPresent is unsupported so beforeInvocation = true misuse fails fast" {
        shouldThrow<UnsupportedOperationException> {
            cache.evictIfPresent("resource-4")
        }
    }

    // Runs cache.evict(key) inside a simulated transaction and completes it with
    // [completionStatus], asserting the entry survives until completion.
    fun evictInTransaction(key: String, completionStatus: Int) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            cache.evict(key)
            // deferred: still cached until the transaction completes
            redisTemplate.hasKey("test-lease::$key") shouldBe true
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(completionStatus) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    "evict inside a transaction is deferred and runs after commit" {
        cache.get("resource-11", Callable { "value" })

        evictInTransaction("resource-11", TransactionSynchronization.STATUS_COMMITTED)

        redisTemplate.hasKey("test-lease::resource-11") shouldBe false
    }

    "evict still runs when the commit outcome is unknown (e.g. commit timeout)" {
        cache.get("resource-12", Callable { "value" })

        evictInTransaction("resource-12", TransactionSynchronization.STATUS_UNKNOWN)

        redisTemplate.hasKey("test-lease::resource-12") shouldBe false
    }

    "evict is skipped on rollback so the still-valid cached value survives" {
        cache.get("resource-13", Callable { "value" })

        evictInTransaction("resource-13", TransactionSynchronization.STATUS_ROLLED_BACK)

        cache.get("resource-13", Callable { "reloaded" }) shouldBe "value"
    }

    "evict outside a transaction removes the entry immediately" {
        cache.get("resource-14", Callable { "value" })

        cache.evict("resource-14")

        redisTemplate.hasKey("test-lease::resource-14") shouldBe false
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
        // simulate another loader holding the load lease
        val foreignLease = codec.newLease()
        redisTemplate.opsForValue().set("test-lease::resource-9", foreignLease, Duration.ofSeconds(5))

        shouldThrow<UnsupportedOperationException> {
            cache.get("resource-9", Callable { "value" })
        }
    }
})

private data class Point(val x: Int, val y: Int) : java.io.Serializable
