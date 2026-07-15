package bee.brainlatency.redisleasecache.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Domain-level tests for [LeaseCache] against a [FakeLeaseCacheStore]: no Spring, no
 * Redis -- just the single-flight lease protocol itself. Concerns that belong to a
 * concrete adapter (Spring's `Cache` contract, transaction-deferred eviction, Redis
 * serialization) are covered where those adapters live instead.
 */
class LeaseCacheTest : StringSpec({

    val store = FakeLeaseCacheStore()
    val cache = LeaseCache(store, leaseTtl = Duration.ofSeconds(5), valueTtl = Duration.ofSeconds(5))

    "get(valueLoader) loads once on miss then serves the cached value without reloading" {
        val loads = AtomicInteger(0)
        val loader = { "loaded-${loads.incrementAndGet()}" }

        cache.get("resource-1", loader) shouldBe "loaded-1"
        cache.get("resource-1", loader) shouldBe "loaded-1"
        loads.get() shouldBe 1
    }

    "get(valueLoader) negatively caches a null result so the loader is not re-invoked" {
        val loads = AtomicInteger(0)
        val nullLoader: () -> String? = { loads.incrementAndGet(); null }

        cache.get("resource-2", nullLoader).shouldBeNull()
        cache.get("resource-2", nullLoader).shouldBeNull()
        loads.get() shouldBe 1
    }

    "a cached value expires after valueTtl so the next call reloads" {
        val shortValueCache = LeaseCache(store, leaseTtl = Duration.ofSeconds(5), valueTtl = Duration.ofMillis(300))
        val loads = AtomicInteger(0)
        val loader = { "loaded-${loads.incrementAndGet()}" }

        shortValueCache.get("resource-3", loader) shouldBe "loaded-1"
        Thread.sleep(500)
        shortValueCache.get("resource-3", loader) shouldBe "loaded-2"
        loads.get() shouldBe 2
    }

    "evict removes the entry so the next call reloads" {
        cache.get("resource-4") { "value" }

        cache.evict("resource-4") shouldBe true

        cache.get("resource-4") { "reloaded" } shouldBe "reloaded"
    }

    "evict on a key with no entry returns false" {
        cache.evict("never-loaded") shouldBe false
    }

    "a failing loader releases the lease so the next caller reloads immediately" {
        shouldThrow<LeaseCacheLoadException> {
            cache.get("resource-5") { error("boom") }
        }

        // lease released on failure -- no waiting out leaseTtl before the retry
        cache.get("resource-5") { "recovered" } shouldBe "recovered"
    }

    "a zombie loader's failure does not release a lease it no longer holds" {
        val zombieLoader = {
            // simulate our lease being lost and a new holder publishing a fresh value
            cache.evict("resource-6")
            cache.get("resource-6") { "fresh" }
            error("boom")
        }

        shouldThrow<LeaseCacheLoadException> {
            cache.get("resource-6", zombieLoader)
        }
        // the fenced release did NOT delete the newer holder's value
        cache.get("resource-6") { "reloaded" } shouldBe "fresh"
    }

    "a zombie loader whose lease was taken over cannot overwrite the newer entry" {
        val zombieLoader = {
            // simulate our lease being lost and a new holder publishing a fresh value
            cache.evict("resource-7")
            cache.get("resource-7") { "fresh" }
            "stale"
        }

        // our caller still gets the value we loaded...
        cache.get("resource-7", zombieLoader) shouldBe "stale"
        // ...but the fenced publish did NOT clobber the newer holder's value
        cache.get("resource-7") { "reloaded" } shouldBe "fresh"
    }

    "a waiter polls while another loader holds the lease and returns the published value" {
        val foreignLeaseToken = store.newLease()
        store.getOrAcquire("resource-8", foreignLeaseToken, Duration.ofSeconds(5))
        Thread {
            Thread.sleep(200)
            store.publish("resource-8", foreignLeaseToken, "published", Duration.ofSeconds(5))
        }.start()

        cache.get("resource-8") { "should-not-load" } shouldBe "published"
    }

    "a waiter takes over when the holder's lease expires without a publish" {
        // a foreign lease that dies without publishing
        val foreignLeaseToken = store.newLease()
        store.getOrAcquire("resource-9", foreignLeaseToken, Duration.ofMillis(200))

        cache.get("resource-9") { "took-over" } shouldBe "took-over"
    }

    "a waiter gives up after waitTimeout while the lease is still held" {
        val impatientCache = LeaseCache(
            store,
            leaseTtl = Duration.ofSeconds(5),
            valueTtl = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(50),
            waitTimeout = Duration.ofMillis(300),
        )
        val foreignLeaseToken = store.newLease()
        store.getOrAcquire("resource-10", foreignLeaseToken, Duration.ofSeconds(5))

        shouldThrow<IllegalStateException> {
            impatientCache.get("resource-10") { "value" }
        }
    }
})
