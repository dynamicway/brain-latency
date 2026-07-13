package bee.brainlatency.redisleasecache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate

/**
 * Exercises the `@Cacheable`/`@CacheEvict` contract [TransactionAwareEvictCache] enforces
 * through real Spring AOP -- [RedisLeaseCacheConfiguration] wires the cache manager onto
 * a real Redis, and [CacheableProbe] carries the annotations -- rather than calling the
 * `Cache` interface directly as the lower-level tests in [RedisLeaseCacheTest] do.
 */
@SpringBootTest(
    classes = [
        RedisLeaseCacheConfiguration::class,
        CacheableProbe::class,
        LeaseCacheableContractTest.TestRedisConnectionConfig::class,
    ],
)
class LeaseCacheableContractTest(
    private val probe: CacheableProbe,
    private val redisTemplate: RedisTemplate<String, ByteArray>,
) : StringSpec({

    afterTest {
        redisTemplate.delete(redisTemplate.keys("probe::*") ?: emptySet())
    }

    // Counter deltas rather than absolute "loaded-N" values, so these don't depend on
    // execution order or on other tests in this spec having touched the shared bean.

    "@Cacheable(sync = true) loads once then serves the cached value" {
        val before = probe.loads.get()

        val first = probe.loadSynced("cacheable-1")
        val second = probe.loadSynced("cacheable-1")

        second shouldBe first
        probe.loads.get() shouldBe before + 1
    }

    "@Cacheable without sync = true fails fast because the cache only supports sync = true reads" {
        shouldThrow<UnsupportedOperationException> {
            probe.loadUnsynced("cacheable-2")
        }
    }

    "@CacheEvict (beforeInvocation = false, the default) evicts after the method runs so the next call reloads" {
        probe.loadSynced("cacheable-3")

        probe.evict("cacheable-3")

        redisTemplate.hasKey("probe::cacheable-3") shouldBe false
    }

    "@CacheEvict(beforeInvocation = true) fails fast because eviction before invocation isn't supported" {
        shouldThrow<UnsupportedOperationException> {
            probe.evictBeforeInvocation("cacheable-4")
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)

    @Configuration
    class TestRedisConnectionConfig {
        // LettuceConnectionFactory implements DisposableBean, so Spring calls destroy()
        // on context shutdown without an explicit destroyMethod.
        @Bean
        fun redisConnectionFactory(): RedisConnectionFactory =
            LettuceConnectionFactory("localhost", 6379).apply { afterPropertiesSet() }
    }
}
