package app.libretag.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** NIP-59 "An Example" (nostr-protocol/nips 59.md) plus round trips of our NIP-17 DMs. */
class Nip17Test {
    private val v = Json.parseToJsonElement(String(javaClass.getResourceAsStream("/nip59.example.json")!!.readBytes())).jsonObject
    private fun ev(k: String) = NostrEvent.fromJson(v[k]!!.jsonObject)!!
    private val authorSk = v["author_sk"]!!.jsonPrimitive.content.unhex()!!
    private val recipientSk = v["recipient_sk"]!!.jsonPrimitive.content.unhex()!!
    private val recipientPk = NostrEvent.xonly(recipientSk)

    @Test fun exampleVectorUnwraps() {
        val wrap = ev("wrap")
        assertTrue(wrap.verify())
        assertEquals(recipientPk.hex(), wrap.tags.single { it[0] == "p" }[1])
        val o = Nip59.unwrap(recipientSk, wrap)!!
        assertEquals(ev("seal").id, o.seal.id)
        assertEquals(ev("rumor").id, o.rumor.id)
        assertEquals("Are you going to the party tonight?", o.rumor.content)
        assertEquals(NostrEvent.xonly(authorSk).hex(), o.rumor.pubkey)
    }

    @Test fun exampleRumorIdMatches() {
        val r = ev("rumor")
        assertEquals(r.id, r.computeId())
    }

    @Test fun dmRoundTrip() {
        val now = 1_800_000_000L
        val w = Nip17.dm(authorSk, recipientPk, "batt 9 %", now, now + 14 * 86400)
        assertEquals(Nip59.KIND_WRAP, w.wrap.kind)
        assertTrue(w.wrap.verify())
        assertEquals(NostrEvent.xonly(w.wrapSk).hex(), w.wrap.pubkey)
        assertNotEquals(NostrEvent.xonly(authorSk).hex(), w.wrap.pubkey)
        assertTrue(w.wrap.createdAt in now - Nip59.TWO_DAYS_S..now)
        assertEquals((now + 14 * 86400).toString(), w.wrap.tags.single { it[0] == "expiration" }[1])
        val o = Nip59.unwrap(recipientSk, w.wrap)!!
        assertEquals(Nip17.KIND_DM, o.rumor.kind)
        assertEquals("batt 9 %", o.rumor.content)
        assertEquals(now, o.rumor.createdAt)
        assertEquals(recipientPk.hex(), o.rumor.tags.single { it[0] == "p" }[1])
        assertTrue(o.seal.tags.any { it[0] == "expiration" })
        assertEquals("", o.rumor.sig)
    }

    @Test fun wrongRecipientCannotOpen() {
        val w = Nip17.dm(authorSk, recipientPk, "hi", 1_800_000_000L)
        assertNull(Nip59.unwrap(ByteArray(32) { 7 }, w.wrap))
    }

    @Test fun sealByAnotherKeyIsRejected() {
        // a rumor claiming the author, sealed by someone else: impersonation (NIP-17 MUST check)
        val mallory = ByteArray(32) { 9 }
        val rumor = NostrEvent(NostrEvent.xonly(authorSk).hex(), 1_800_000_000L, 14, listOf(listOf("p", recipientPk.hex())), "fake").withId()
        val seal = Nip59.seal(mallory, recipientPk, rumor, 1_800_000_000L)
        val wrap = Nip59.wrap(ByteArray(32) { 5 }, recipientPk, seal, 1_800_000_000L)
        assertNull(Nip59.unwrap(recipientSk, wrap))
    }

    @Test fun timesAreRandomized() {
        val r = SecureRandom()
        val times = (0 until 20).map { Nip59.randomPast(1_800_000_000L, r) }.toSet()
        assertTrue(times.size > 1)
        assertTrue(times.all { it in 1_800_000_000L - Nip59.TWO_DAYS_S..1_800_000_000L })
    }

    @Test fun parseInboxOnlyDmRelays() {
        val sk = ByteArray(32) { 3 }
        val e10050 = NostrEvent.sign(sk, 1, InboxRelays.KIND_DM_RELAYS, listOf(listOf("relay", "wss://inbox.example.com/")), "")
        val e10002 = NostrEvent.sign(sk, 1, InboxRelays.KIND_RELAY_LIST, listOf(listOf("r", "wss://read.example.com")), "")
        assertEquals(listOf("wss://inbox.example.com"), InboxRelays.dmRelays(listOf(e10050, e10002), e10050.pubkey))
        assertEquals(emptyList<String>(), InboxRelays.dmRelays(listOf(e10002), e10002.pubkey))
        assertNotNull(e10050)
    }
}
