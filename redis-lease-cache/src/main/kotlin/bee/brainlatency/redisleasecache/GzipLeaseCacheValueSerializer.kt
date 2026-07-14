package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheValueSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Decorates another [LeaseCacheValueSerializer], gzip-compressing its payload bytes on
 * the way out and inflating them back on the way in. Useful for large values (verbose
 * JSON especially) where the smaller Redis footprint is worth the compress/decompress
 * cost.
 *
 * The compressed output is arbitrary binary -- gzip frames start with the magic bytes
 * `0x1F 0x8B`, neither of which is a valid stand-alone UTF-8 sequence -- which is exactly
 * why the store's value channel is a raw `ByteArray` (see [RedisTemplateLeaseCacheStore])
 * and not a `String`: routing these bytes through a charset would re-encode the invalid
 * ones to `U+FFFD` and corrupt the payload irrecoverably. A `ByteArray` channel hands the
 * bytes back exactly as written, so wrapping any serializer in compression stays safe.
 */
class GzipLeaseCacheValueSerializer(
    private val delegate: LeaseCacheValueSerializer,
) : LeaseCacheValueSerializer {

    override fun serialize(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(delegate.serialize(value)) }
        return out.toByteArray()
    }

    override fun deserialize(bytes: ByteArray): Any? {
        val inflated = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        return delegate.deserialize(inflated)
    }
}
