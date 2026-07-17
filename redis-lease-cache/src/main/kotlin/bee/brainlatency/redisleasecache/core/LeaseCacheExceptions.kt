package bee.brainlatency.redisleasecache.core

import java.time.Duration

/**
 * What a [LeaseCache.get] can fail with. The three cases are kept apart because a caller
 * reacts to each differently: the origin said no ([OriginLoadException]), the lease store
 * itself is broken ([LeaseStoreException]), or nobody published in time
 * ([LeaseWaitTimeoutException]). Sealed, so `when` over them is exhaustive and a caller
 * that wants "anything the cache can throw" can still catch this one type.
 */
sealed class LeaseCacheException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * The caller's own value loader threw. The load lease has already been released (or was
 * already lost), so the next caller reloads immediately rather than waiting out the TTL.
 * The [cause] is the loader's exception, untouched -- this type only says *where* the
 * failure came from, so an adapter can map it onto its framework's contract.
 */
class OriginLoadException(key: String, override val cause: Throwable) :
    LeaseCacheException("value loader failed for key [$key]", cause)

/**
 * The lease store itself failed -- i.e. the client exhausted its own retries and gave up,
 * so this is a genuine outage rather than a lost race. Distinct from
 * [OriginLoadException]: the origin may be perfectly healthy and the loaded value may even
 * be in hand; what broke is the coordination layer, so the value could not be published or
 * the lease could not be released.
 */
class LeaseStoreException(key: String, override val cause: Throwable) :
    LeaseCacheException("lease store failed for key [$key]", cause)

/**
 * Another loader held the lease for longer than `waitTimeout` without publishing, and we
 * were never granted it ourselves. Bounds the poll loop: without it a waiter would spin
 * for as long as some other holder keeps renewing its hold on the key.
 */
class LeaseWaitTimeoutException(key: String, waitTimeout: Duration) :
    LeaseCacheException("timed out after $waitTimeout waiting to load or be published key [$key]", null)
