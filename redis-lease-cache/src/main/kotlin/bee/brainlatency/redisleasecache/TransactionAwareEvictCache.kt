package bee.brainlatency.redisleasecache

import org.springframework.cache.Cache
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Defers [evict] until the surrounding transaction completes, forwarding every other
 * [Cache] operation straight through to [delegate]. The entry must outlive the
 * transaction so concurrent readers keep hitting the still-valid value while it's in
 * flight, and eviction must run on every outcome except a certain rollback --
 * including commit timeout, where the database may have committed. Keying off
 * completion status rather than `afterCommit` covers that unknown-outcome case: the
 * entry is evicted anyway and the next read reloads, rather than potentially serving a
 * value the database no longer holds. Only a certain rollback keeps the entry, since
 * the database is then known unchanged.
 *
 * Unlike Spring's own `TransactionAwareCacheDecorator` (which only ever evicts
 * `afterCommit`), this also evicts on an unknown completion status, for the reason
 * above. Outside a transaction, [evict] runs immediately.
 */
class TransactionAwareEvictCache(private val delegate: Cache) : Cache by delegate {

    // Kotlin's interface delegation forwards only abstract members; a Java default
    // method like evictIfPresent falls through to Spring's default body (evict + return
    // false), silently bypassing whatever the delegate decided for it -- for
    // SpringRedisLeaseCache, its fail-fast rejection. Forward it explicitly.
    override fun evictIfPresent(key: Any): Boolean = delegate.evictIfPresent(key)

    override fun evict(key: Any) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delegate.evict(key)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    delegate.evict(key)
                }
            }
        })
    }
}
