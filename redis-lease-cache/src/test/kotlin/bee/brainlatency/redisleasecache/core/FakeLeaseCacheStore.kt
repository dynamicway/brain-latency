package bee.brainlatency.redisleasecache.core

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory [LeaseCacheStore] for exercising [LeaseCache] without Redis or Spring:
 * plain maps and monotonic tokens stand in for the real store's atomic script and
 * byte framing, while preserving the same lease-fencing contract the domain relies on.
 */
class FakeLeaseCacheStore : LeaseCacheStore {

    private sealed interface StoredEntry {
        val expiresAt: Long

        data class Leased(val token: LeaseToken, override val expiresAt: Long) : StoredEntry
        data class Valued(val value: Any?, override val expiresAt: Long) : StoredEntry
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, StoredEntry>()
    private val tokenSequence = AtomicLong()

    override fun newLease(): LeaseToken = LeaseToken(tokenSequence.incrementAndGet().toString().toByteArray())

    override fun getOrAcquire(key: String, leaseToken: LeaseToken, leaseTtl: Duration): LeaseCacheEntry = synchronized(lock) {
        when (val current = liveEntry(key)) {
            is StoredEntry.Valued -> LeaseCacheEntry.Value(current.value)
            is StoredEntry.Leased -> LeaseCacheEntry.Held(current.token)
            null -> {
                entries[key] = StoredEntry.Leased(leaseToken, expiresAt(leaseTtl))
                LeaseCacheEntry.Held(leaseToken)
            }
        }
    }

    override fun publish(key: String, leaseToken: LeaseToken, value: Any?, valueTtl: Duration): Unit = synchronized(lock) {
        val current = entries[key]
        if (current is StoredEntry.Leased && current.token.matches(leaseToken)) {
            entries[key] = StoredEntry.Valued(value, expiresAt(valueTtl))
        }
    }

    override fun release(key: String, leaseToken: LeaseToken): Unit = synchronized(lock) {
        val current = entries[key]
        if (current is StoredEntry.Leased && current.token.matches(leaseToken)) {
            entries.remove(key)
        }
    }

    override fun evict(key: String): Boolean = synchronized(lock) {
        entries.remove(key) != null
    }

    private fun liveEntry(key: String): StoredEntry? =
        entries[key]?.takeIf { it.expiresAt > System.nanoTime() }

    private fun expiresAt(ttl: Duration): Long = System.nanoTime() + ttl.toNanos()
}
