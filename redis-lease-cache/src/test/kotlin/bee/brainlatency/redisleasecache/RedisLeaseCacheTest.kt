package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.cache.Cache
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.Callable

class RedisLeaseCacheTest : StringSpec({

    val connectionFactory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    val redisTemplate = RedisTemplate<String, ByteArray>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer = StringRedisSerializer()
        valueSerializer = RedisSerializer.byteArray()
        afterPropertiesSet()
    }
    val codec = LeaseCacheCodec(RedisSerializerLeaseCacheSerializer(RedisSerializer.java(), Any::class.java))
    val store = RedisTemplateLeaseCacheStore(redisTemplate, codec)
    val cache = TransactionAwareEvictCache("test-lease", LeaseCache(store, Duration.ofSeconds(5), Duration.ofSeconds(5)))

    afterTest {
        redisTemplate.delete(redisTemplate.keys("test-lease::*") ?: emptySet())
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

    "a failing loader is surfaced through Spring's Cache.ValueRetrievalException" {
        shouldThrow<Cache.ValueRetrievalException> {
            cache.get("resource-15", Callable<String> { error("boom") })
        }

        // lease released on failure -- no waiting out leaseTtl before the retry
        cache.get("resource-15", Callable { "recovered" }) shouldBe "recovered"
    }

    "get(valueLoader) caches an arbitrary object type via the injected serializer" {
        val loaded = cache.get("resource-10", Callable { Point(3, 4) })
        loaded shouldBe Point(3, 4)

        // served from cache and deserialized back to the same type, loader not re-run
        cache.get("resource-10", Callable { Point(9, 9) }) shouldBe Point(3, 4)
    }
})

private data class Point(val x: Int, val y: Int) : java.io.Serializable
