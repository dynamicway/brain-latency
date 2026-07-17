package bee.brainlatency.redisleasecache.core

import java.util.*

/**
 * A lease credential: whoever holds these bytes has acquired (or is trying to acquire)
 * the load lease for a key. Minted by [new].
 *
 * The wrapped bytes are private -- nothing outside this class can read or compare them
 * directly. [matches] is the only way to compare two tokens: it uses `contentEquals`,
 * never this class's generated `equals`/`==`, which would delegate to [ByteArray]'s own
 * `equals` (reference equality) and be silently wrong. [toBytes] and [fromBytes] are the
 * escape hatches for [LeaseCacheEntryCodec], the one place that has to put a token on the
 * wire and rebuild it off the wire again. They are scoped `internal` so library consumers
 * outside this module never see them.
 */
@JvmInline
value class LeaseToken private constructor(private val bytes: ByteArray) {

    companion object {
        /** Mints a fresh, uniquely-identified lease token to attempt acquisition with. */
        fun new() = LeaseToken(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))

        /** Rebuilds a token from its raw identity bytes read back off the wire. */
        internal fun fromBytes(bytes: ByteArray): LeaseToken = LeaseToken(bytes)
    }

    /** True if [other] identifies the same lease. */
    fun matches(other: LeaseToken): Boolean = bytes.contentEquals(other.bytes)

    internal fun toBytes(): ByteArray = bytes
}
