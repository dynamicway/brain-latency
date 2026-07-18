package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.FakeLeaseCacheStore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [RedisLeaseCacheManager]'s per-name TTL resolution against a
 * [FakeLeaseCacheStore] -- no Spring, no Redis. Concerns owned by the adapter itself
 * ([TransactionAwareEvictCache]'s Cache contract) are covered where that adapter lives.
 */
class RedisLeaseCacheManagerTest : StringSpec({

    "a name with an overridden valueTtl expires independently of a name without one" {
        val store = FakeLeaseCacheStore()
        val manager = RedisLeaseCacheManager(
            store,
            defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)),
            cacheTtlOverrides = mapOf("short-lived" to LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofMillis(200))),
        )
        val overriddenLoads = AtomicInteger(0)
        val defaultLoads = AtomicInteger(0)

        manager.getCache("short-lived").get("k", Callable { "loaded-${overriddenLoads.incrementAndGet()}" })
        manager.getCache("default-ttl").get("k", Callable { "loaded-${defaultLoads.incrementAndGet()}" })

        Thread.sleep(400)

        // the override's 200ms valueTtl has lapsed, so this name reloads
        manager.getCache("short-lived").get("k", Callable { "loaded-${overriddenLoads.incrementAndGet()}" })
        // the unconfigured name is still on the manager's 5s default, so it does not
        manager.getCache("default-ttl").get("k", Callable { "loaded-${defaultLoads.incrementAndGet()}" })

        overriddenLoads.get() shouldBe 2
        defaultLoads.get() shouldBe 1
    }

    "getCache is lazy-once per name: repeated calls for the same name return the same Cache instance" {
        val manager = RedisLeaseCacheManager(FakeLeaseCacheStore(), defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)))

        manager.getCache("same") shouldBe manager.getCache("same")
    }

    "getCacheNames reflects every name requested through getCache" {
        val manager = RedisLeaseCacheManager(FakeLeaseCacheStore(), defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)))

        manager.getCache("a")
        manager.getCache("b")

        manager.getCacheNames() shouldBe setOf("a", "b")
    }
})
