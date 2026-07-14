package bee.brainlatency.redisleasecache

import bee.brainlatency.redisleasecache.core.LeaseCacheCodec
import bee.brainlatency.redisleasecache.core.LeaseCacheEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer

/**
 * Worked examples of plugging different value-serialization strategies into the
 * [LeaseCacheCodec]. Both a JSON strategy (payload is UTF-8 *text*) and a gzip strategy
 * (payload is arbitrary *binary*) frame, store, and decode without a live Redis -- the
 * codec's `valueEntry`/`decode` is the whole value round-trip. The point they make
 * together: the store's value channel is a raw `ByteArray`
 * ([RedisTemplateLeaseCacheStore] uses `RedisTemplate<String, ByteArray>`), so it carries
 * both text and binary payloads back byte-for-byte -- which is what lets you swap in
 * compression at all.
 */
class LeaseCacheValueSerializerExamplesTest : StringSpec({

    // A small, realistic cache value: a user record.
    val record = linkedMapOf<String, Any>("id" to 42, "name" to "brain-latency", "active" to true)

    // Frames [value] as a stored entry and decodes it back, exactly as the store would.
    fun LeaseCacheCodec.roundTrip(value: Any?): Any? =
        (decode(valueEntry(value)) as LeaseCacheEntry.Value).value

    // Returns just the value payload -- the framed entry minus its leading tag byte.
    fun LeaseCacheCodec.payloadOf(value: Any?): ByteArray = valueEntry(value).let { it.copyOfRange(1, it.size) }

    "example 1 -- JSON: value is serialized to readable UTF-8 text and round-trips intact" {
        // Jackson 3 serializer; no default typing, so a plain map becomes plain JSON.
        val codec = LeaseCacheCodec(
            RedisSerializerLeaseCacheValueSerializer(GenericJacksonJsonRedisSerializer.builder().build()),
        )

        codec.roundTrip(record) shouldBe record

        // The payload is valid, human-readable JSON text...
        val payload = codec.payloadOf(record)
        val json = String(payload, Charsets.UTF_8)
        json shouldBe """{"id":42,"name":"brain-latency","active":true}"""
        // ...and being clean UTF-8, it even survives a String round-trip unchanged -- this
        // is the case where a String value channel would have been fine.
        json.toByteArray(Charsets.UTF_8) shouldBe payload
    }

    "example 2 -- gzip(JSON): value is compressed to binary, shrinks, and still round-trips" {
        val json = RedisSerializerLeaseCacheValueSerializer(GenericJacksonJsonRedisSerializer.builder().build())
        val plainCodec = LeaseCacheCodec(json)
        val gzipCodec = LeaseCacheCodec(GzipLeaseCacheValueSerializer(json))

        // A large, repetitive value where compression clearly pays off.
        val bulky = linkedMapOf<String, Any>("id" to 42, "blob" to "brain-latency-".repeat(500))

        // Correctness first: compression is transparent to the caller.
        gzipCodec.roundTrip(bulky) shouldBe bulky

        // The compressed payload is meaningfully smaller than the raw JSON.
        val gzipped = gzipCodec.payloadOf(bulky)
        val raw = plainCodec.payloadOf(bulky)
        (gzipped.size < raw.size) shouldBe true

        // But it is arbitrary binary: it opens with the gzip magic bytes 0x1F 0x8B, and
        // 0x8B is a lone UTF-8 continuation byte -- illegal on its own. So a String value
        // channel would re-encode it to U+FFFD and corrupt the payload; the raw-ByteArray
        // channel is what keeps gzip safe here.
        gzipped[0] shouldBe 0x1F.toByte()
        gzipped[1] shouldBe 0x8B.toByte()
        val corrupted = String(gzipped, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        (corrupted.contentEquals(gzipped)) shouldBe false
    }
})
