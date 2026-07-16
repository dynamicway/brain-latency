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

    val store = FakeLeaseCacheStore<Any>()
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
        shouldThrow<LeaseCacheOriginException> {
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

        shouldThrow<LeaseCacheOriginException> {
            cache.get("resource-6", zombieLoader)
        }
        // the fenced release did NOT delete the newer holder's value
        cache.get("resource-6") { "reloaded" } shouldBe "fresh"
    }

    "a zombie loader whose lease was taken over yields to the newer entry instead of returning its own stale load" {
        val zombieLoader = {
            // simulate our lease being lost and a new holder publishing a fresh value
            cache.evict("resource-7")
            cache.get("resource-7") { "fresh" }
            "stale"
        }

        // the fenced publish didn't land, so we retry rather than hand back "stale" --
        // the retry finds the newer holder's value already cached and returns that
        cache.get("resource-7", zombieLoader) shouldBe "fresh"
        cache.get("resource-7") { "reloaded" } shouldBe "fresh"
    }

    "an eviction that races only the first publish attempt is recovered by retrying the load" {
        val attempts = AtomicInteger(0)
        val loader = {
            val n = attempts.incrementAndGet()
            if (n == 1) {
                // simulate a plain evict landing while we're still off loading --
                // unlike the zombie scenario above, nobody takes the lease over
                store.evict("resource-11")
            }
            "attempt-$n"
        }

        cache.get("resource-11", loader) shouldBe "attempt-2"
        attempts.get() shouldBe 2
        // the retry's publish landed for real this time -- it's actually cached
        cache.get("resource-11") { "should-not-load" } shouldBe "attempt-2"
    }

    "an eviction racing every retry attempt eventually gives up and returns the last loaded value without caching it" {
        val impatientCache = LeaseCache(
            store,
            leaseTtl = Duration.ofSeconds(5),
            valueTtl = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(20),
            waitTimeout = Duration.ofMillis(150),
        )
        val attempts = AtomicInteger(0)
        val alwaysRacingLoader = {
            val n = attempts.incrementAndGet()
            store.evict("resource-12")
            "attempt-$n"
        }

        val result = impatientCache.get("resource-12", alwaysRacingLoader)

        result shouldBe "attempt-${attempts.get()}"
        (attempts.get() > 1) shouldBe true
        // never landed -- the next call is a genuine miss, not a replay of the give-up value
        val reloads = AtomicInteger(0)
        impatientCache.get("resource-12") { "reloaded-${reloads.incrementAndGet()}" } shouldBe "reloaded-1"
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

    "a waiter that times out with fast store round-trips blames the origin load and fails closed" {
        val impatientCache = LeaseCache(
            store,
            leaseTtl = Duration.ofSeconds(5),
            valueTtl = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(50),
            waitTimeout = Duration.ofMillis(300),
        )
        // the in-memory store answers instantly, so the wait was spent waiting on a
        // holder that never publishes -- an origin bottleneck, not a cache one
        val foreignLeaseToken = store.newLease()
        store.getOrAcquire("resource-10", foreignLeaseToken, Duration.ofSeconds(5))

        val ex = shouldThrow<LeaseCacheOriginException> {
            impatientCache.get("resource-10") { "should-not-load" }
        }
        // an origin fault with no cause: the timeout flavour, not a loader throw -- and it
        // did NOT fall back to the loader, since the origin is presumed struggling
        ex.cause.shouldBeNull()
    }

    "a waiter that times out because store round-trips dominate fails open and loads from the origin" {
        // a store whose every getOrAcquire is slow: the wait is eaten by cache I/O, not
        // by a holder sitting on the lease -- the cache tier is the bottleneck
        val slowStore = SlowGetOrAcquireStore(FakeLeaseCacheStore(), delay = Duration.ofMillis(80))
        val failOpenCache = LeaseCache(
            slowStore,
            leaseTtl = Duration.ofSeconds(5),
            valueTtl = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(1),
            waitTimeout = Duration.ofMillis(200),
        )
        // a foreign holder keeps every poll returning Held so we never get granted
        val foreignLeaseToken = slowStore.newLease()
        slowStore.getOrAcquire("resource-13", foreignLeaseToken, Duration.ofSeconds(5))

        val loads = AtomicInteger(0)
        // fell open: the loader ran once and its value was returned, no exception
        failOpenCache.get("resource-13") { "origin-${loads.incrementAndGet()}" } shouldBe "origin-1"
        loads.get() shouldBe 1
    }
})

// A store whose getOrAcquire is artificially slow, so a waiter's polling round-trips
// dominate the wait -- exercising the cache-tier-stall branch of LeaseCache's timeout
// diagnosis. Every other operation delegates straight through.
private class SlowGetOrAcquireStore(
    private val delegate: FakeLeaseCacheStore<Any>,
    private val delay: Duration,
) : LeaseCacheStore<Any> {
    override fun newLease(): LeaseToken = delegate.newLease()

    override fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry<Any> {
        Thread.sleep(delay.toMillis())
        return delegate.getOrAcquire(key, leaseToken, leaseTtl)
    }

    override fun publish(key: String, leaseToken: LeaseToken, value: Any?, valueTtl: Duration): Boolean =
        delegate.publish(key, leaseToken, value, valueTtl)

    override fun release(key: String, leaseToken: LeaseToken) = delegate.release(key, leaseToken)

    override fun evict(key: String): Boolean = delegate.evict(key)
}
