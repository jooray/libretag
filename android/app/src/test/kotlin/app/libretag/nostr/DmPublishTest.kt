package app.libretag.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmPublishTest {
    private val sk = ByteArray(32) { (it + 1).toByte() }
    private val ev = NostrEvent.sign(sk, 1_700_000_000, 4, listOf(listOf("p", "ab".repeat(32))), "x?iv=y")
    private val url = "wss://auth.example.com"

    private fun session(authSk: ByteArray? = sk): Pair<PublishSession, MutableList<String>> {
        val sent = mutableListOf<String>()
        return PublishSession(url, ev, authSk, { sent += it }, { 1_700_000_100 }) to sent
    }
    private fun frame(s: String) = Json.parseToJsonElement(s) as JsonArray

    @Test fun plainOk() {
        val (s, sent) = session()
        s.onOpen()
        assertEquals("EVENT", frame(sent.single())[0].jsonPrimitive.content)
        assertEquals(RelayAck(url, true, ""), s.onMessage("""["OK","${ev.id}",true,""]"""))
    }

    @Test fun duplicateCountsAsAccepted() {
        val (s, _) = session()
        s.onOpen()
        assertTrue(s.onMessage("""["OK","${ev.id}",false,"duplicate: have it"]""")!!.ok)
    }

    @Test fun authRequiredThenAuthThenResend() {
        val (s, sent) = session()
        s.onOpen()
        assertNull(s.onMessage("""["AUTH","chal-1"]"""))
        val auth = frame(sent[1])
        assertEquals("AUTH", auth[0].jsonPrimitive.content)
        val authEv = NostrEvent.fromJson(auth[1] as JsonObject)!!
        assertEquals(22242, authEv.kind)
        assertTrue(authEv.verify())
        assertEquals(ev.pubkey, authEv.pubkey) // signed by the tag's key
        assertTrue(listOf("relay", url) in authEv.tags && listOf("challenge", "chal-1") in authEv.tags)
        assertNull(s.onMessage("""["OK","${ev.id}",false,"auth-required: you must auth"]"""))
        assertEquals(2, sent.size) // waits for the AUTH answer
        assertNull(s.onMessage("""["OK","${authEv.id}",true,""]"""))
        assertEquals(3, sent.size)
        assertEquals("EVENT", frame(sent[2])[0].jsonPrimitive.content)
        assertTrue(s.onMessage("""["OK","${ev.id}",true,""]""")!!.ok)
    }

    @Test fun authRequiredAfterAuthOkResendsOnce() {
        val (s, sent) = session()
        s.onOpen()
        s.onMessage("""["AUTH","c"]""")
        val authId = NostrEvent.fromJson(frame(sent[1])[1] as JsonObject)!!.id
        s.onMessage("""["OK","$authId",true,""]""")
        assertNull(s.onMessage("""["OK","${ev.id}",false,"auth-required: x"]"""))
        assertEquals(3, sent.size)
        val r = s.onMessage("""["OK","${ev.id}",false,"auth-required: x"]""")!!
        assertFalse(r.ok)
        assertEquals("auth-required: x", r.reason)
    }

    @Test fun refusalKeepsReason() {
        val (s, _) = session()
        s.onOpen()
        val r = s.onMessage("""["OK","${ev.id}",false,"blocked: kind not allowed on this relay"]""")!!
        assertFalse(r.ok)
        assertEquals("blocked: kind not allowed on this relay", r.reason)
        assertEquals("auth.example.com", r.host)
    }

    @Test fun withoutAuthKeyAuthRequiredIsFinal() {
        val (s, sent) = session(null)
        s.onOpen()
        assertNull(s.onMessage("""["AUTH","c"]"""))
        assertEquals(1, sent.size)
        assertFalse(s.onMessage("""["OK","${ev.id}",false,"auth-required: x"]""")!!.ok)
    }

    @Test fun profileHasNoTagDetails() {
        val p = TagProfile.event(sk, 1)
        assertEquals(0, p.kind)
        val o = Json.parseToJsonElement(p.content) as JsonObject
        assertEquals(setOf("name", "about", "picture"), o.keys)
        assertEquals("LibreTag", o["name"]!!.jsonPrimitive.content)
        assertEquals(
            """{"name":"LibreTag","about":"Messages from your LibreTag tag (battery alerts).","picture":"https://cypherpunk.today/libretag/app/icon-512.png"}""",
            p.content,
        )
    }
}
