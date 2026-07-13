package bee.brainlatency.redisleasecache.core

import java.time.Duration

/**
 * The tuning knobs a [LeaseCache] instance needs, factored out so a manager can hold one
 * per cache name instead of forcing every name to share a single policy -- different
 * caches legitimately want different [valueTtl]s, and [leaseTtl]/[waitTimeout] follow
 * from how long that cache's own loader takes.
 */
data class LeaseCacheConfiguration(
    val leaseTtl: Duration,
    val valueTtl: Duration,
    val pollInterval: Duration = Duration.ofMillis(50),
    val waitTimeout: Duration = leaseTtl.multipliedBy(2),
) {
    fun toLeaseCache(store: LeaseCacheStore): LeaseCache =
        LeaseCache(store, leaseTtl, valueTtl, pollInterval, waitTimeout)
}
