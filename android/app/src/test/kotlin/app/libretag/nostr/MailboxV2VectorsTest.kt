package app.libretag.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** mailbox-v2.json (host/tests/vectors, PROTOCOL.md 4.1): the rules shared with the bridges. */
class MailboxV2VectorsTest {
    private val v = Json.parseToJsonElement(String(javaClass.getResourceAsStream("/mailbox-v2.json")!!.readBytes())).jsonObject
    private val bridge = v["bridge_pubkey"]!!.jsonPrimitive.content

    private fun tags(e: JsonElement) = e.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }

    @Test fun targeting() {
        for (c in v["targeting"]!!.jsonArray.map { it.jsonObject }) {
            val note = c["note"]!!.jsonPrimitive.content
            val t = BridgeTargets.targeting(tags(c["tags"]!!))
            if (!c["valid"]!!.jsonPrimitive.boolean) { assertNull(note, t); continue }
            assertNotNull(note, t)
            val p = c["parsed"]!!.jsonObject
            assertEquals(note, p["format"]!!.jsonPrimitive.int, t!!.format)
            assertEquals(note, p["route"]!!.jsonPrimitive.content, t.route)
            assertEquals(note, p["p"]!!.jsonArray.map { it.jsonPrimitive.content }, t.p)
            assertEquals(note, p["r"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }, t.r)
        }
    }

    @Test fun proofAndHops() {
        for (c in v["proof"]!!.jsonArray.map { it.jsonObject })
            assertEquals(c["proof"]!!.jsonPrimitive.content,
                BridgeTargets.claimProof(c["p"]!!.jsonPrimitive.content.unhex()!!, c["pubkey"]!!.jsonPrimitive.content))
        for (c in v["hops_class"]!!.jsonArray.map { it.jsonObject })
            assertEquals(c["class"]!!.jsonPrimitive.content, BridgeTargets.hopsClass(c["hops"]!!.jsonPrimitive.intOrNull))
    }

    @Test fun lock() {
        for (c in v["lock"]!!.jsonArray.map { it.jsonObject }) {
            val p = c["p"]!!.jsonPrimitive.content.unhex()!!
            for (k in c["keys"]!!.jsonArray.map { it.jsonObject })
                assertEquals(k["note"]!!.jsonPrimitive.content, k["fits"]!!.jsonPrimitive.boolean,
                    app.libretag.proto.LibreTag.commitMatches(k["pubkey"]!!.jsonPrimitive.content.unhex()!!, p))
        }
    }

    @Test fun events() {
        val keys = app.libretag.proto.Keys(ByteArray(32) { it.toByte() })
        for (c in v["events"]!!.jsonArray.map { it.jsonObject }) {
            val note = c["note"]!!.jsonPrimitive.content
            val ev = NostrEvent.fromJson(c["event"]!!.jsonObject)!!
            assertEquals(note, ev.id, ev.computeId())
            val n = c["n"]!!.jsonPrimitive.long
            val pkN = app.libretag.proto.LibreTag.mailboxPk(keys.cmd(app.libretag.proto.LibreTag.epoch(n)), n).hex()
            assertEquals(note, c["signed_by_pk_n"]!!.jsonPrimitive.boolean, ev.pubkey == pkN)
            val t = BridgeTargets.targeting(ev.tags)
            assertEquals(note, c["valid"]!!.jsonPrimitive.boolean, t != null)
            if (t != null) assertEquals(note, c["for_bridge"]!!.jsonPrimitive.boolean, t.open || bridge in t.p)
        }
    }
}
