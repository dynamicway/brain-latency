package bee.brainlatency.redisleasecache.core

/**
 * A lease credential: whoever holds these bytes has acquired (or is trying to acquire)
 * the load lease for a key. Minted by [LeaseCacheStore.newLease].
 *
 * The wrapped bytes are private -- nothing outside this class can read or compare them
 * directly. [matches] is the only way to compare two tokens: it uses `contentEquals`,
 * never this class's generated `equals`/`==`, which would delegate to [ByteArray]'s own
 * `equals` (reference equality) and be silently wrong. [toBytes] is the one escape
 * hatch, scoped `internal` so only this module -- the Redis I/O behind the
 * [LeaseCacheStore] port -- can reach the raw bytes; library consumers outside it never
 * see them.
 */
@JvmInline
value class LeaseToken(private val bytes: ByteArray) {

    /** True if [other] identifies the same lease. */
    fun matches(other: LeaseToken): Boolean = bytes.contentEquals(other.bytes)

    internal fun toBytes(): ByteArray = bytes
}
