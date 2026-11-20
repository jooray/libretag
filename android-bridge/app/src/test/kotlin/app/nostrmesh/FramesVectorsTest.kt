package app.nostrmesh

import app.nostrmesh.frames.FrameException
import app.nostrmesh.frames.Frames
import app.nostrmesh.frames.Fragments
import app.nostrmesh.nostr.Bech32
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The same vector files the Python bridge and the C firmware run. */
class FramesVectorsTest {
    private fun load(name: String) =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject

    private val nostr = load("nostr-frames-v1.json")
    private val road = load("road-events-v1.json")

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun ev(o: JsonObject) = NostrEvent.fromJson(o["event"]!!.jsonObject)!!
    private fun resolver(v: JsonObject): (ByteArray) -> List<ByteArray> {
        val keys = (v["registry"] as? JsonArray)?.map { it.jsonPrimitive.content.unhex()!! } ?: emptyList()
        return { r -> keys.filter { it.copyOf(4).contentEquals(r) } }
    }

    @Test
    fun nostrValidDecode() {
        for (e in nostr["valid"]!!.jsonArray) {
            val v = e.jsonObject
            if (v.s("profile") == "libretag.public") {
                // variant A (135-byte isomorphic report) is gone: LT2 reports ride #libretag
                try {
                    Frames.decode(v.s("frame").unhex()!!, resolve = resolver(v))
                    fail("libretag.public decoded")
                } catch (x: FrameException) {
                    assertEquals("UNKNOWN_PROFILE", x.code)
                }
                continue
            }
            val d = Frames.decode(v.s("frame").unhex()!!, resolve = resolver(v))
            assertEquals(v.s("name"), ev(v), d.event)
            assertEquals(v.s("name"), v.s("profile"), d.profileName)
        }
    }

    @Test
    fun dmEncodeMatchesVectors() {
        for (e in nostr["valid"]!!.jsonArray) {
            val v = e.jsonObject
            if (!v.s("profile").startsWith("dm4")) continue
            val prof = Frames.PROFILE_NAMES.entries.first { it.value == v.s("profile") }.key
            assertEquals(v.s("name"), v.s("frame"), Frames.encodeDm(ev(v), prof).hex())
            // the shape of the event picks dm4 or dm4x by itself
            assertEquals(v.s("name"), v.s("frame"), Frames.encodeDm(ev(v), ref = prof in Frames.REF_PROFILES).hex())
        }
    }

    @Test
    fun dm4xFramesAreByteIdenticalToDm4ExceptTheProfileByte() {
        val valid = nostr["valid"]!!.jsonArray.map { it.jsonObject }
        val x = valid.filter { it.s("profile").startsWith("dm4x") }
        assertEquals(2, x.size)
        for (v in x) {
            val e = ev(v)
            val d = Frames.decode(v.s("frame").unhex()!!, resolve = resolver(v))
            assertEquals(listOf("expiration", (e.createdAt + Frames.DM4X_TTL_S).toString()), d.event.tags[1])
            assertEquals(v.s("profile").replace("dm4x", "dm4"), d.policyName)
            // the same bytes under the dm4 twin profile decode to an event without the expiration
            val f = v.s("frame").unhex()!!
            f[0] = (f[0] - 2).toByte()
            val twin = Frames.decode(f, resolve = resolver(v), verify = false)
            assertEquals(listOf(e.tags[0]), twin.event.tags)
            assertEquals(e.content, twin.event.content)
        }
    }

    @Test
    fun dmEncodeInvalid() {
        for (el in nostr["encode_invalid"]!!.jsonArray) {
            val v = el.jsonObject
            val e = ev(v)
            if (e.kind != 4) continue
            val prof = v["profile"]?.jsonPrimitive?.content?.let { p -> Frames.PROFILE_NAMES.entries.first { it.value == p }.key }
            try {
                if (prof != null) Frames.encodeDm(e, prof) else Frames.encodeDm(e, ref = false)
                fail("${v.s("name")} encoded")
            } catch (x: FrameException) {
                assertEquals(v.s("name"), v.s("error"), x.code)
            }
        }
    }

    @Test
    fun nostrDecodeInvalid() {
        for (e in nostr["decode_invalid"]!!.jsonArray) {
            val v = e.jsonObject
            try {
                Frames.decode(v.s("frame").unhex()!!, resolve = resolver(v))
                fail("${v.s("name")} decoded")
            } catch (x: FrameException) {
                assertEquals(v.s("name"), v.s("error"), x.code)
            }
        }
    }

    @Test
    fun fragmentsReassemble() {
        val v = nostr["fragments"]!!.jsonArray[0].jsonObject
        val parts = v["fragments"]!!.jsonArray.map { it.jsonPrimitive.content.unhex()!! }
        // data fragments only (no parity) in reverse order
        val r = Fragments.Reassembler()
        val got = parts.dropLast(1).reversed().map { r.feed(it, 0) }.last()
        assertEquals(v.s("frame"), got!!.hex())
        assertEquals(ev(v), Frames.decode(got, maxLen = Frames.MAX_REASSEMBLED).event)
        assertEquals(parts.dropLast(1).map { it.hex() }, Fragments.split(got, 165).map { it.hex() })
    }

    @Test
    fun roadbeaconVectors() {
        for (sec in listOf("valid", "decode_only")) for (e in road[sec]!!.jsonArray) {
            val v = e.jsonObject
            val d = Frames.decode(v.s("frame").unhex()!!, Frames.DATA_TYPE_ROAD)
            assertEquals(v.s("name"), ev(v), d.event)
        }
        for (e in road["decode_invalid"]!!.jsonArray) {
            val v = e.jsonObject
            try {
                Frames.decode(v.s("frame").unhex()!!, Frames.DATA_TYPE_ROAD)
                fail("${v.s("name")} decoded")
            } catch (x: FrameException) {
                assertEquals(v.s("name"), v.s("error"), x.code)
            }
        }
    }

    @Test
    fun npubRoundTrip() {
        val pk = ByteArray(32) { it.toByte() }
        assertTrue(Bech32.pubkey(Bech32.npub(pk))!!.contentEquals(pk))
        assertEquals(null, Bech32.pubkey("npub1qqqq"))
        assertEquals(pk.hex(), Bech32.pubkey(pk.hex())!!.hex())
    }

    @Suppress("unused") private fun JsonObject.opt(k: String) = this[k]?.jsonPrimitive?.contentOrNull
}
