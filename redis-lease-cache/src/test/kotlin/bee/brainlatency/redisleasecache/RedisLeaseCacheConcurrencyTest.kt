package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCache
import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import bee.brainlatency.redisleasecache.core.LeaseToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency tests against a real Redis instance: verify that the Lua scripts behind
 * [RedisTemplateLeaseCacheStore] are atomic under genuine concurrent load -- many real
 * threads racing a real server -- rather than merely correct in the single-threaded,
 * sequentially-driven scenarios [RedisLeaseCacheTest] and the [FakeLeaseCacheStore]-backed
 * domain tests exercise.
 */
class RedisLeaseCacheConcurrencyTest : StringSpec({

    val connectionFactory = LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    val redisTemplate = RedisTemplate<String, ByteArray>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer = StringRedisSerializer()
        valueSerializer = RedisSerializer.byteArray()
        afterPropertiesSet()
    }
    val codec = LeaseCacheCodec(RedisSerializerLeaseCacheSerializer(RedisSerializer.java(), Any::class.java))
    val store = RedisTemplateLeaseCacheStore(redisTemplate, codec)

    afterTest {
        redisTemplate.delete(redisTemplate.keys("concurrency::*") ?: emptySet())
    }

    afterSpec {
        connectionFactory.destroy()
    }

    // Fires [threadCount] copies of [action] from a fixed pool, releasing them all
    // through a shared latch so they contend on Redis at (as close to) the same instant,
    // then waits for every thread to finish before handing back their results.
    fun <T> runConcurrently(threadCount: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        try {
            val futures = (0 until threadCount).map { i ->
                pool.submit<T> {
                    startGate.await()
                    action(i)
                }
            }
            startGate.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    "GET_OR_ACQUIRE is atomic under concurrent callers: exactly one of many racing tokens is granted the load lease" {
        val key = "concurrency::resource-1"
        val tokens = (0 until 20).map { LeaseToken.new() }

        val entries = runConcurrently(tokens.size) { i ->
            store.getOrAcquire(key, tokens[i], Duration.ofSeconds(5)) as LeaseCacheEntry.Held
        }

        val grantedCount = tokens.indices.count { i -> entries[i].isHeldBy(tokens[i]) }
        grantedCount shouldBe 1
        // every caller -- winner and losers alike -- was handed back the very same
        // entry: the one winning token, never a lease of their own
        entries.forEach { entry -> tokens.count { entry.isHeldBy(it) } shouldBe 1 }
    }

    "PUBLISH is fenced under concurrent writers: only the entry's real lease token can land a value" {
        val key = "concurrency::resource-2"
        val realToken = LeaseToken.new()
        store.getOrAcquire(key, realToken, Duration.ofSeconds(5))
        val foreignTokens = (0 until 19).map { LeaseToken.new() }

        runConcurrently(foreignTokens.size + 1) { i ->
            if (i == 0) {
                store.publish(key, realToken, "real-value", Duration.ofSeconds(5))
            } else {
                store.publish(key, foreignTokens[i - 1], "foreign-$i", Duration.ofSeconds(5))
            }
        }

        val settled = store.getOrAcquire(key, LeaseToken.new(), Duration.ofSeconds(5))
        (settled as LeaseCacheEntry.Value).value shouldBe "real-value"
    }

    "RELEASE is fenced under concurrent releasers: only the entry's real lease token can release it" {
        val key = "concurrency::resource-3"
        val realToken = LeaseToken.new()
        store.getOrAcquire(key, realToken, Duration.ofSeconds(5))
        val foreignTokens = (0 until 19).map { LeaseToken.new() }

        runConcurrently(foreignTokens.size) { i -> store.release(key, foreignTokens[i]) }

        // the real lease survived every foreign release attempt
        val stillHeld = store.getOrAcquire(key, LeaseToken.new(), Duration.ofSeconds(5)) as LeaseCacheEntry.Held
        stillHeld.isHeldBy(realToken) shouldBe true
    }

    "LeaseCache.get is single-flight under real concurrent load: the loader runs exactly once and every caller gets its value" {
        val key = "concurrency::resource-4"
        val cache = LeaseCache(store, leaseTtl = Duration.ofSeconds(5), valueTtl = Duration.ofSeconds(5))
        val loads = AtomicInteger(0)

        val results = runConcurrently(20) {
            cache.get(key) {
                Thread.sleep(200)
                "loaded-${loads.incrementAndGet()}"
            }
        }

        loads.get() shouldBe 1
        results.toSet() shouldBe setOf("loaded-1")
    }
})
