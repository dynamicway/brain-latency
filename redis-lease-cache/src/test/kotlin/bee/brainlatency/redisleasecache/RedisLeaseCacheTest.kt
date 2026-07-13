package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
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
import java.util.concurrent.atomic.AtomicInteger

class RedisLeaseCacheTest : StringSpec({

    val connectionFactory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    val redisTemplate = RedisTemplate<String, ByteArray>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer = StringRedisSerializer()
        valueSerializer = RedisSerializer.byteArray()
        afterPropertiesSet()
    }
    val codec = LeaseCacheCodec(RedisSerializerLeaseCacheValueSerializer(RedisSerializer.java()))
    val store = RedisTemplateLeaseCacheStore(redisTemplate, codec)
    val cache = SpringRedisLeaseCache("test-lease", LeaseCache(store, Duration.ofSeconds(5), Duration.ofSeconds(5)))

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

    "a failing loader releases the lease so the next caller reloads immediately" {
        shouldThrow<Cache.ValueRetrievalException> {
            cache.get("resource-15", Callable<String> { error("boom") })
        }

        // lease released on failure -- no waiting out leaseTtl before the retry
        cache.get("resource-15", Callable { "recovered" }) shouldBe "recovered"
    }

    "a zombie loader's failure does not release a lease it no longer holds" {
        val zombieLoader = Callable<String> {
            // simulate our lease being lost and a new holder publishing a fresh value
            cache.evict("resource-16")
            cache.get("resource-16", Callable { "fresh" })
            error("boom")
        }

        shouldThrow<Cache.ValueRetrievalException> {
            cache.get("resource-16", zombieLoader)
        }
        // the fenced release did NOT delete the newer holder's value
        cache.get("resource-16", Callable { "reloaded" }) shouldBe "fresh"
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
        val shortValueCache = SpringRedisLeaseCache("short-value", LeaseCache(store, Duration.ofSeconds(5), Duration.ofMillis(300)))
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

    "a waiter polls while another loader holds the lease and returns the published value" {
        // simulate another loader holding the load lease and publishing shortly after
        val foreignLeaseToken = store.newLease()
        redisTemplate.opsForValue().set("test-lease::resource-9", foreignLeaseToken, Duration.ofSeconds(5))
        Thread {
            Thread.sleep(200)
            store.publish("test-lease::resource-9", foreignLeaseToken, "published", Duration.ofSeconds(5))
        }.start()

        cache.get("resource-9", Callable { "should-not-load" }) shouldBe "published"
    }

    "a waiter takes over when the holder's lease expires without a publish" {
        // a foreign lease that dies without publishing
        val foreignLeaseToken = store.newLease()
        redisTemplate.opsForValue().set("test-lease::resource-17", foreignLeaseToken, Duration.ofMillis(200))

        cache.get("resource-17", Callable { "took-over" }) shouldBe "took-over"
    }

    "a waiter gives up after waitTimeout while the lease is still held" {
        val impatientCache = SpringRedisLeaseCache(
            "test-lease",
            LeaseCache(
                store,
                leaseTtl = Duration.ofSeconds(5),
                valueTtl = Duration.ofSeconds(5),
                pollInterval = Duration.ofMillis(50),
                waitTimeout = Duration.ofMillis(300),
            ),
        )
        val foreignLeaseToken = store.newLease()
        redisTemplate.opsForValue().set("test-lease::resource-18", foreignLeaseToken, Duration.ofSeconds(5))

        shouldThrow<IllegalStateException> {
            impatientCache.get("resource-18", Callable { "value" })
        }
    }
})

private data class Point(val x: Int, val y: Int) : java.io.Serializable
