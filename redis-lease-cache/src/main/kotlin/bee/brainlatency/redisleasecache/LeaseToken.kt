package bee.brainlatency.redisleasecache

/**
 * A lease credential: whoever holds these bytes has acquired (or is trying to acquire)
 * the load lease for a key. Minted by [LeaseCacheStore.newLease].
 *
 * Wraps a plain [ByteArray] rather than exposing one directly at the [LeaseCacheStore]
 * boundary, so the type signature itself says "this is a lease token", not just its
 * name -- passing the wrong bytes (say, a framed value entry) is a compile error
 * instead of a runtime one.
 *
 * Compare tokens by content, never by [equals] -- [equals] is generated from the
 * wrapped [ByteArray]'s own `equals`, which is reference equality. Ownership is
 * decided by [LeaseCacheEntry.Held.isHeldBy], which uses `contentEquals` explicitly.
 */
@JvmInline
value class LeaseToken(val bytes: ByteArray)
