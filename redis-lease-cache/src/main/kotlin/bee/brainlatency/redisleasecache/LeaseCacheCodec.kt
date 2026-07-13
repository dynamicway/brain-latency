package bee.brainlatency.redisleasecache

import org.springframework.data.redis.serializer.RedisSerializer
import java.util.UUID

/**
 * Owns how a [LeaseTokenCache] entry is framed as bytes: a leading tag byte marks
 * the entry as a held load lease (`T`), a cached value (`V`), or a negatively
 * cached null (`N`), and the value payload is produced by [valueSerializer]. The
 * cache orchestrates against the decoded [LeaseCacheEntry] and never touches the
 * byte layout, so the framing (or serializer, or compression) can change here alone.
 */
class LeaseCacheCodec(private val valueSerializer: RedisSerializer<Any>) {

    /** A fresh, uniquely-identified load-lease entry to attempt acquisition with. */
    fun newLease(): ByteArray =
        frame(TOKEN_TAG, UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))

    /** Frames a loaded value, or a null as a negative-cache marker, for storage. */
    fun valueEntry(value: Any?): ByteArray =
        if (value == null) frame(NULL_TAG)
        else frame(VALUE_TAG, valueSerializer.serialize(value) ?: ByteArray(0))

    /** Interprets a raw stored entry. */
    fun decode(raw: ByteArray): LeaseCacheEntry =
        when (raw.firstOrNull()) {
            VALUE_TAG -> LeaseCacheEntry.Value(valueSerializer.deserialize(raw.copyOfRange(1, raw.size)))
            NULL_TAG -> LeaseCacheEntry.Value(null)
            TOKEN_TAG -> LeaseCacheEntry.Held(raw)
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

/** A decoded cache entry: either a stored value or a held load lease. */
sealed interface LeaseCacheEntry {
    /** A cached value, or `null` for a negatively cached miss. */
    data class Value(val value: Any?) : LeaseCacheEntry

    /** A load lease is held by someone; who is an opaque byte-level detail. */
    class Held(private val raw: ByteArray) : LeaseCacheEntry {
        /** True if this held lease is the one [lease] identifies (i.e. ours). */
        fun isHeldBy(lease: ByteArray): Boolean = raw.contentEquals(lease)
    }
}
