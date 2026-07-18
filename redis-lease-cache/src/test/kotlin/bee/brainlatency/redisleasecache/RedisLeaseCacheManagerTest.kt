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

    "setTtl retunes one name's TTL at runtime, leaving a name left on the default untouched" {
        val manager = RedisLeaseCacheManager(FakeLeaseCacheStore(), defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)))
        manager.setTtl("short-lived", LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofMillis(200)))
        val shortLoads = AtomicInteger(0)
        val defaultLoads = AtomicInteger(0)

        manager.getCache("short-lived").get("k", Callable { "loaded-${shortLoads.incrementAndGet()}" })
        manager.getCache("default-ttl").get("k", Callable { "loaded-${defaultLoads.incrementAndGet()}" })

        Thread.sleep(400)

        // the retuned 200ms valueTtl has lapsed, so this name reloads
        manager.getCache("short-lived").get("k", Callable { "loaded-${shortLoads.incrementAndGet()}" })
        // the untouched name is still on the 5s default, so it does not
        manager.getCache("default-ttl").get("k", Callable { "loaded-${defaultLoads.incrementAndGet()}" })

        shortLoads.get() shouldBe 2
        defaultLoads.get() shouldBe 1
    }

    "setTtl on a name already built through getCache takes effect for its next publish" {
        val manager = RedisLeaseCacheManager(FakeLeaseCacheStore(), defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)))
        val loads = AtomicInteger(0)

        // first published under the 5s default
        manager.getCache("orders").get("k", Callable { "loaded-${loads.incrementAndGet()}" })

        // retune, then force a fresh publish so the new 200ms valueTtl is what gets stored
        manager.setTtl("orders", LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofMillis(200)))
        manager.getCache("orders").evict("k")
        manager.getCache("orders").get("k", Callable { "loaded-${loads.incrementAndGet()}" })

        Thread.sleep(400)

        // stored under 200ms now, so it has lapsed and reloads
        manager.getCache("orders").get("k", Callable { "loaded-${loads.incrementAndGet()}" })

        loads.get() shouldBe 3
    }

    "a setTtl on one manager is seen by another sharing the same ttl store" {
        // two managers over one store stand in for two server instances; the shared
        // LeaseCacheTtlStore is what a distributed deployment backs with Redis.
        val store = FakeLeaseCacheStore()
        val ttlStore = InMemoryLeaseCacheTtlStore()
        val instanceA = RedisLeaseCacheManager(store, defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)), ttlStore = ttlStore)
        val instanceB = RedisLeaseCacheManager(store, defaultTtl = LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofSeconds(5)), ttlStore = ttlStore)
        val loads = AtomicInteger(0)

        // A shortens "orders"; B resolves that name's TTL through the shared store
        instanceA.setTtl("orders", LeaseCacheTtl(Duration.ofSeconds(5), Duration.ofMillis(200)))

        instanceB.getCache("orders").get("k", Callable { "loaded-${loads.incrementAndGet()}" })
        Thread.sleep(400)
        // B published under the propagated 200ms valueTtl, so it has lapsed and reloads
        instanceB.getCache("orders").get("k", Callable { "loaded-${loads.incrementAndGet()}" })

        loads.get() shouldBe 2
    }
})
