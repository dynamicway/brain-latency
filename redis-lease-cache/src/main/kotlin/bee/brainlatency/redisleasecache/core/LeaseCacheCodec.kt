package bee.brainlatency.redisleasecache.core

/**
 * Owns how a [LeaseCache] entry is framed as bytes: a leading tag byte marks the entry
 * as a held load lease (`T`), a cached value (`V`), or a negatively cached null (`N`),
 * and the value payload is produced by the [serializer] strategy for values of type [V].
 * The cache orchestrates against the decoded [LeaseCacheEntry] and never touches the byte
 * layout, so the framing (or serializer, or compression) can change here alone.
 *
 * Framing only: it wraps a lease token that already exists rather than generating
 * one -- minting the token itself is [LeaseToken.new]'s job.
 */
class LeaseCacheCodec<V : Any>(private val serializer: LeaseCacheSerializer<V>) {

    /** Frames [token] as a held load-lease entry. */
    fun leaseEntry(token: ByteArray): ByteArray = frame(TOKEN_TAG, token)

    /** Frames a loaded value, or a null as a negative-cache marker, for storage. */
    fun valueEntry(value: V?): ByteArray =
        if (value == null) frame(NULL_TAG)
        else frame(VALUE_TAG, serializer.serialize(value))

    /** Interprets a raw stored entry. */
    fun decode(raw: ByteArray): LeaseCacheEntry<V> =
        when (raw.firstOrNull()) {
            VALUE_TAG -> LeaseCacheEntry.Value(serializer.deserialize(raw.copyOfRange(1, raw.size)))
            NULL_TAG -> LeaseCacheEntry.Value(null)
            TOKEN_TAG -> LeaseCacheEntry.Held(LeaseToken.fromBytes(raw.copyOfRange(1, raw.size)))
            else -> error("unrecognized cache entry tag: ${raw.firstOrNull()}")
        }

    private fun frame(tag: Byte, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(tag) + payload

    private companion object {
        const val TOKEN_TAG = 'T'.code.toByte()
        const val VALUE_TAG = 'V'.code.toByte()
        const val NULL_TAG = 'N'.code.toByte()
    }
}
