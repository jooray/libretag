package app.libretag.signer

import app.libretag.nostr.Bech32
import app.libretag.nostr.Nip44
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How we read signer answers (NIP-55 extras / provider columns, NIP-46 replies), without a device. */
class SignerParseTest {
    private val sk = ByteArray(32) { 0x42 }
    private val pk = NostrEvent.xonly(sk).hex()
    private val unsigned = NostrEvent(pk, 1_800_000_000, 30078, listOf(listOf("d", "ab".repeat(16))), "payload").withId()
    private val signed = NostrEvent.sign(sk, unsigned.createdAt, unsigned.kind, unsigned.tags, unsigned.content)

    private fun extras(vararg kv: Pair<String, String>): (String) -> String? = kv.toMap()::get

    @Test fun publicKeyAsNpubOrHex() {
        assertEquals(pk to "com.greenart7c3.nostrsigner", Nip55.parsePublicKey(extras("result" to Bech32.npub(NostrEvent.xonly(sk)), "package" to "com.greenart7c3.nostrsigner")))
        assertEquals(pk to null, Nip55.parsePublicKey(extras("result" to pk)))
        assertNull(Nip55.parsePublicKey(extras("result" to "nonsense")))
        assertNull(Nip55.parsePublicKey(extras()))
    }

    @Test fun signatureInEveryShape() {
        assertEquals(signed, Nip55.parseSigned(unsigned, extras("result" to signed.sig)))
        assertEquals(signed, Nip55.parseSigned(unsigned, extras("signature" to signed.sig.uppercase())))
        assertEquals(signed, Nip55.parseSigned(unsigned, extras("event" to signed.toJson().toString())))
        assertEquals(signed, Nip55.parseSigned(unsigned, extras("results" to "[{\"id\":\"${unsigned.id}\",\"result\":\"${signed.sig}\"}]")))
    }

    @Test fun refusesAnotherEventOrABadSignature() {
        val other = NostrEvent.sign(sk, unsigned.createdAt + 1, unsigned.kind, unsigned.tags, unsigned.content)
        assertNull(Nip55.parseSigned(unsigned, extras("event" to other.toJson().toString())))
        assertNull(Nip55.parseSigned(unsigned, extras("result" to other.sig)))
        assertNull(Nip55.parseSigned(unsigned, extras("result" to "00".repeat(64))))
        assertNull(Nip55.parseSigned(unsigned, extras("results" to "[{\"id\":\"${other.id}\",\"result\":\"${signed.sig}\"}]")))
    }

    @Test fun unsignedJsonHasNoSig() {
        val o = Json.parseToJsonElement(Nip55.unsignedJson(unsigned)).jsonObject
        assertEquals(setOf("id", "pubkey", "created_at", "kind", "tags", "content"), o.keys)
        assertEquals(unsigned.id, o["id"]!!.jsonPrimitive.content)
    }

    @Test fun nip44TextAnswer() {
        assertEquals("Ag…", Nip55.parseText(extras("result" to "Ag…")))
        assertEquals("old", Nip55.parseText(extras("signature" to "old")))
        assertNull(Nip55.parseText(extras("result" to "")))
    }

    @Test fun nip46RequestAndReplyRoundTrip() {
        val client = ByteArray(32) { 1 }
        val signer = ByteArray(32) { 2 }
        val req = Nip46.requestJson("id1", "nip44_encrypt", listOf(pk, "{\"v\":1}"))
        val o = Json.parseToJsonElement(req) as JsonObject
        assertEquals("nip44_encrypt", o["method"]!!.jsonPrimitive.content)
        assertEquals(listOf(pk, "{\"v\":1}"), o["params"]!!.jsonArray.map { it.jsonPrimitive.content })
        // the transport: client -> signer and back, NIP-44 between the two throwaway keys
        val wire = Nip44.encrypt(req, Nip44.conversationKey(client, NostrEvent.xonly(signer)))
        assertEquals(req, Nip44.decrypt(wire, Nip44.conversationKey(signer, NostrEvent.xonly(client))))
        assertEquals(Nip46.Reply("id1", "Ag==", null), Nip46.parseReply("{\"id\":\"id1\",\"result\":\"Ag==\"}"))
        assertEquals(Nip46.Reply("id1", null, "denied"), Nip46.parseReply("{\"id\":\"id1\",\"error\":\"denied\"}"))
        assertEquals(Nip46.Reply("id1", "auth_url", "https://example.org/approve"),
            Nip46.parseReply("{\"id\":\"id1\",\"result\":\"auth_url\",\"error\":\"https://example.org/approve\"}"))
        assertNull(Nip46.parseReply("[]"))
        assertTrue(Nip46.newSecret().length == 32)
    }
}
