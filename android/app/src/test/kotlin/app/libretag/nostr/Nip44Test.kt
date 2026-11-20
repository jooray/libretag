package app.libretag.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** Official NIP-44 v2 vectors (github.com/paulmillr/nip44, nip44.vectors.json, MIT). */
class Nip44Test {
    private val raw = javaClass.getResourceAsStream("/nip44.vectors.json")!!.readBytes()
    private val v2 = Json.parseToJsonElement(String(raw)).jsonObject["v2"]!!.jsonObject
    private val valid = v2["valid"]!!.jsonObject
    private val invalid = v2["invalid"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.b(k: String) = s(k).unhex()!!

    @Test fun vectorFileIsTheOfficialOne() =
        assertEquals("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040", MessageDigest.getInstance("SHA-256").digest(raw).hex())

    @Test fun conversationKeys() {
        for (e in valid["get_conversation_key"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(e.s("conversation_key"), Nip44.conversationKey(e.b("sec1"), e.b("pub2")).hex())
        }
    }

    @Test fun messageKeys() {
        val mk = valid["get_message_keys"]!!.jsonObject
        val ck = mk.b("conversation_key")
        for (e in mk["keys"]!!.jsonArray.map { it.jsonObject }) {
            val (a, b, c) = Nip44.messageKeys(ck, e.b("nonce"))
            assertEquals(e.s("chacha_key"), a.hex())
            assertEquals(e.s("chacha_nonce"), b.hex())
            assertEquals(e.s("hmac_key"), c.hex())
        }
    }

    @Test fun paddedLengths() {
        for (e in valid["calc_padded_len"]!!.jsonArray) {
            val (len, padded) = e.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(padded, Nip44.calcPaddedLen(len))
        }
    }

    @Test fun encryptDecrypt() {
        for (e in valid["encrypt_decrypt"]!!.jsonArray.map { it.jsonObject }) {
            val sec1 = e.b("sec1")
            val pub2 = NostrEvent.xonly(e.b("sec2"))
            val ck = Nip44.conversationKey(sec1, pub2)
            assertEquals(e.s("conversation_key"), ck.hex())
            // the same key from the other side
            assertEquals(ck.hex(), Nip44.conversationKey(e.b("sec2"), NostrEvent.xonly(sec1)).hex())
            assertEquals(e.s("payload"), Nip44.encrypt(e.s("plaintext"), ck, e.b("nonce")))
            assertEquals(e.s("plaintext"), Nip44.decrypt(e.s("payload"), ck))
            assertTrue(Nip44.isPayload(e.s("payload")))
        }
    }

    @Test fun longMessages() {
        for (e in valid["encrypt_decrypt_long_msg"]!!.jsonArray.map { it.jsonObject }) {
            val pt = e.s("pattern").repeat(e["repeat"]!!.jsonPrimitive.int)
            assertEquals(e.s("plaintext_sha256"), sha256(pt.toByteArray()).hex())
            val payload = Nip44.encrypt(pt, e.b("conversation_key"), e.b("nonce"))
            assertEquals(e.s("payload_sha256"), sha256(payload.toByteArray()).hex())
            assertEquals(pt, Nip44.decrypt(payload, e.b("conversation_key")))
        }
    }

    @Test fun invalidDecrypt() {
        for (e in invalid["decrypt"]!!.jsonArray.map { it.jsonObject }) {
            try {
                Nip44.decrypt(e.s("payload"), e.b("conversation_key"))
                fail("should fail: ${e.s("note")}")
            } catch (x: Nip44.Nip44Exception) {
                // expected
            }
        }
    }

    @Test fun invalidConversationKeys() {
        for (e in invalid["get_conversation_key"]!!.jsonArray.map { it.jsonObject }) {
            try {
                Nip44.conversationKey(e.b("sec1"), e.b("pub2"))
                fail("should fail: ${e.s("note")}")
            } catch (x: Nip44.Nip44Exception) {
                // expected
            }
        }
    }

    @Test fun invalidLengths() {
        val ck = ByteArray(32) { 1 }
        for (n in invalid["encrypt_msg_lengths"]!!.jsonArray.map { it.jsonPrimitive.int }) {
            try {
                Nip44.encrypt("a".repeat(n), ck)
                fail("length $n should fail")
            } catch (x: Nip44.Nip44Exception) {
                // expected
            }
        }
    }

    @Test fun isPayloadRejectsOtherContent() {
        assertFalse(Nip44.isPayload(""))
        assertFalse(Nip44.isPayload("{\"v\":1}"))
        assertFalse(Nip44.isPayload("AQAB" + "A".repeat(200))) // version 1 (the passphrase copy)
        assertFalse(Nip44.isPayload("abc?iv=def")) // NIP-04
    }
}
