package app.libretag.backup

import app.libretag.nostr.Bech32
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import app.libretag.nostr.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Random

class KeyBackupTest {
    private val fast = KeyBackup.Params(16, 1, 1)
    private val pass = "  copper  kettle under seven   bridges "
    private val d = KeyBackup.derive(pass, fast)
    private val payload = BackupPayload(
        created = 1_800_000_000,
        owner = Bech32.npub(ByteArray(32) { 0x11 }),
        tags = listOf(
            BackupTag("Car", "00".repeat(32), "vehicle", "red-stripe"),
            BackupTag("Backpack \"blue\"", "0123456789abcdef".repeat(4), "thing", "blue-triangle"),
        ),
    )

    // ------------------------------------------------------------------ scrypt (RFC 7914 section 12)

    private fun scrypt(p: String, s: String, n: Int, r: Int, par: Int) =
        Scrypt.derive(p.toByteArray(), s.toByteArray(), n, r, par, 64).hex()

    @Test fun rfc7914Vector1() = assertEquals(
        "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
            "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
        scrypt("", "", 16, 1, 1),
    )

    @Test fun rfc7914Vector2() = assertEquals(
        "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
            "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
        scrypt("password", "NaCl", 1024, 8, 16),
    )

    @Test fun rfc7914Vector3() = assertEquals(
        "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
            "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
        scrypt("pleaseletmein", "SodiumChloride", 16384, 8, 1),
    )

    /** RFC 7914 section 11: PBKDF2-HMAC-SHA256 with c = 1 and c = 80000 */
    @Test fun pbkdf2Vectors() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Scrypt.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64).hex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            Scrypt.pbkdf2Sha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64).hex(),
        )
    }

    /** RFC 7914 section 8 */
    @Test fun salsa208Vector() {
        val inp = ("7e879a214f3ec9867ca940e641718f26baee555b8c61c1b50df846116dcd3b1d" +
            "ee24f319df9b3d8514121e4b5ac5aa3276021d2909c74829edebc68db8b8c25e").unhex()!!
        val w = IntArray(16) { java.nio.ByteBuffer.wrap(inp, 4 * it, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int }
        Scrypt.salsa208(w)
        val out = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> w.forEach { b.putInt(it) } }.array()
        assertEquals(
            "a41f859c6608cc993b81cacb020cef05044b2181a2fd337dfd7b1c6396682f29" +
                "b4393168e3c9e6bcfe6bc5b7a06d96bae424cc102c91745c24ad673dc7618f81",
            out.hex(),
        )
    }

    // ------------------------------------------------------------------ passphrase

    @Test fun normalisation() {
        assertEquals("copper kettle under seven bridges", KeyBackup.normalize(pass))
        assertEquals("Ab fi c", KeyBackup.normalize("　Ab\t\nﬁ c﻿"))
        assertEquals("café", KeyBackup.normalize("café")) // NFKC composes
        assertEquals("ABC def", KeyBackup.normalize("ＡＢＣ def")) // fullwidth folds, case kept
        assertEquals(KeyBackup.derive("copper kettle under seven bridges", fast).locator, d.locator)
        assertNotEquals(KeyBackup.derive("Copper kettle under seven bridges", fast).locator, d.locator)
    }

    @Test fun strength() {
        assertEquals(KeyBackup.Strength.TOO_SHORT, KeyBackup.strength(""))
        assertEquals(KeyBackup.Strength.TOO_SHORT, KeyBackup.strength("correcthorsebatterystaplelongword"))
        assertEquals(KeyBackup.Strength.TOO_SHORT, KeyBackup.strength("a b c d e f")) // 6 words, 11 chars
        assertEquals(KeyBackup.Strength.TOO_SHORT, KeyBackup.strength("three long words here".replace(" here", "")))
        assertEquals(KeyBackup.Strength.OK, KeyBackup.strength("copper kettle under seven"))
        assertEquals(KeyBackup.Strength.OK, KeyBackup.strength(pass))
    }

    // ------------------------------------------------------------------ derivation, events

    @Test fun derivedShapes() {
        assertTrue(d.locator.matches(Regex("[0-9a-f]{32}")))
        assertEquals(32, d.sk.size)
        assertEquals(32, d.encKey.size)
        assertEquals(NostrEvent.xonly(d.sk).hex(), d.pubkeyHex)
        assertNotEquals(d.sk.hex(), d.encKey.hex())
    }

    @Test fun sealAndOpenRoundTrip() {
        val ev = KeyBackup.seal(d, payload, Random(1), now = 1_800_000_100)
        assertEquals(30078, ev.kind)
        assertEquals(listOf(listOf("d", d.locator), listOf("alt", "LibreTag encrypted key backup")), ev.tags)
        assertEquals(d.pubkeyHex, ev.pubkey)
        assertTrue(ev.verify())
        assertEquals(payload, KeyBackup.open(d, ev))
        // through relay JSON and back
        assertEquals(payload, KeyBackup.open(d, NostrEvent.fromJson(Json.parseToJsonElement(ev.toJson().toString()).jsonObject)!!))
        // random nonce: two seals differ
        assertNotEquals(ev.content, KeyBackup.seal(d, payload, now = 1_800_000_100).content)
    }

    @Test fun wrongPassphraseOrTamperingFails() {
        val ev = KeyBackup.seal(d, payload, Random(2), now = 1_800_000_100)
        val other = KeyBackup.derive("copper kettle under eight bridges", fast)
        assertNull(KeyBackup.open(other, ev))
        // same signer, but content flipped and re-signed: GCM rejects it
        val raw = Base64.getDecoder().decode(ev.content).also { it[20] = (it[20].toInt() xor 1).toByte() }
        val forged = NostrEvent.sign(d.sk, ev.createdAt, ev.kind, ev.tags, Base64.getEncoder().encodeToString(raw))
        assertNull(KeyBackup.open(d, forged))
        // valid content but broken signature
        assertNull(KeyBackup.open(d, ev.copy(sig = "00".repeat(64))))
        // a stranger's event with our d tag
        val stranger = NostrEvent.sign(ByteArray(32) { 7 }, ev.createdAt, ev.kind, ev.tags, ev.content)
        assertNull(KeyBackup.open(d, stranger))
        // locator is the AAD: moving the content under another d tag fails
        val moved = NostrEvent.sign(d.sk, ev.createdAt, ev.kind, listOf(listOf("d", "f".repeat(32))), ev.content)
        assertNull(KeyBackup.open(d, moved))
        assertNull(KeyBackup.open(d, NostrEvent.sign(d.sk, 1, 30078, ev.tags, "not base64!")))
    }

    @Test fun restorePicksNewestAndHonoursDeletion() {
        val old = KeyBackup.seal(d, payload, Random(3), now = 1_800_000_000)
        val newer = payload.copy(created = 1_800_000_500, tags = payload.tags.take(1))
        val new = KeyBackup.seal(d, newer, Random(4), now = 1_800_000_500)
        val junk = NostrEvent.sign(ByteArray(32) { 9 }, 1_900_000_000, 30078, new.tags, new.content)
        assertEquals(RestoreResult.NotFound, KeyBackup.restore(d, emptyList()))
        assertEquals(RestoreResult.NotFound, KeyBackup.restore(d, listOf(junk)))
        val r = KeyBackup.restore(d, listOf(old, junk, new))
        assertTrue(r is RestoreResult.Found)
        assertEquals(newer, (r as RestoreResult.Found).payload)

        val del = KeyBackup.deletion(d, now = 1_800_001_000)
        assertEquals(2, del.size)
        assertEquals("", del[0].content)
        assertEquals(30078, del[0].kind)
        assertEquals(listOf("d", d.locator), del[0].tags[0])
        assertEquals(5, del[1].kind)
        assertEquals(listOf("a", "30078:${d.pubkeyHex}:${d.locator}"), del[1].tags[0])
        assertTrue(del.all { it.verify() && it.pubkey == d.pubkeyHex })
        assertEquals(RestoreResult.Deleted, KeyBackup.restore(d, listOf(old, new) + del))
        assertNull(KeyBackup.open(d, del[0]))

        val corrupt = NostrEvent.sign(d.sk, 1_800_002_000, 30078, new.tags, Base64.getEncoder().encodeToString(ByteArray(40) { 1 }))
        assertEquals(RestoreResult.Unreadable, KeyBackup.restore(d, listOf(new, corrupt)))
    }

    @Test fun filterShape() {
        val f = KeyBackup.filter(d)
        assertEquals("[30078]", f["kinds"].toString())
        assertEquals(d.pubkeyHex, f["authors"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(d.locator, f["#d"]!!.jsonArray[0].jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ real parameters, vector file

    private val vectorFile = File("../../docs/vectors/key-backup-v1.json")
    private val vPass = "  Granite owl   counts\tseven ﬁgs  "
    private val vNonce = "000102030405060708090a0b".unhex()!!
    private val vPayload = BackupPayload(
        created = 1_800_000_000,
        owner = Bech32.npub(ByteArray(32) { 0x11 }),
        tags = listOf(
            BackupTag("Car", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "vehicle", "red-stripe"),
            BackupTag("Backpack", "f".repeat(64), "thing", "blue-triangle"),
        ),
    )

    // 12.2 A: a BIP-39 phrase. TEST ONLY: generated with SecureRandom for this vector on 2026-11-16,
    // never any wallet's seed. Typed with odd case and spacing to exercise the normalisation.
    private val bMnemonic = "illegal morning embody alone salmon lab quality hazard waste wrong leaf pair"
    private val bInput = "  Illegal morning EMBODY alone\tsalmon lab  quality hazard waste wrong leaf pair\n"
    private val bNonce = "0c0d0e0f1011121314151617".unhex()!!
    private val bPayload = BackupPayload(
        created = 1_800_000_600,
        owner = Bech32.npub(ByteArray(32) { 0x11 }),
        d = "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
        tags = listOf(
            BackupTag("Keys", "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f", "thing", "blue-triangle", updated = 1_800_000_500),
            BackupTag.tombstone("e".repeat(64), 1_800_000_300),
        ),
    )
    private val bInvalid = mapOf(
        // valid words, wrong checksum (last word changed)
        "checksum" to listOf(
            "illegal morning embody alone salmon lab quality hazard waste wrong leaf leaf",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon",
        ),
        // 11 and 13 words, and a valid 15-word mnemonic (only 12 or 24 are accepted)
        "length" to listOf(
            "illegal morning embody alone salmon lab quality hazard waste wrong leaf",
            "illegal morning embody alone salmon lab quality hazard waste wrong leaf pair pair",
            "decide clay annual afford leave butter idea speak motor thrive weird screen moon bundle panda",
        ),
        "unknown_word" to listOf("illegal morning embody alone salmon lab quality hazard waste wrong leaf bitcoin"),
    )

    private fun buildBip39(): JsonObject {
        val norm = (Bip39.check(bInput) as Bip39.Check.Ok).phrase
        val s = KeyBackup.stretch(norm)
        val dv = KeyBackup.fromSecret(s)
        val ev = KeyBackup.sealWithNonce(dv, bPayload, bNonce, 1_800_000_600)
        return buildJsonObject {
            put("description", "SPEC.md 12.2 A: the passphrase is a BIP-39 mnemonic (English, 12 or 24 words, valid checksum), " +
                "normalised to lowercase words and single spaces, then exactly 12.1. TEST mnemonic generated for this vector, " +
                "never a real wallet's. The plaintext is v1.1: \"d\" of the npub copy, \"updated\" per tag, a removed tag as a tombstone.")
            put("input", bInput)
            put("mnemonic", norm)
            put("entropy", Bip39.toEntropy(norm)!!.hex())
            put("S", s.hex())
            put("d", dv.locator)
            put("sk", dv.sk.hex())
            put("pubkey", dv.pubkeyHex)
            put("kEnc", dv.encKey.hex())
            put("nonce", bNonce.hex())
            put("plaintext", KeyBackup.plaintext(bPayload))
            put("content", ev.content)
            put("created_at", ev.createdAt)
            put("event_id", ev.id)
            put("filter", KeyBackup.filter(dv))
            put("deletion_a", KeyBackup.address(dv))
        }
    }

    private fun buildVector(): JsonObject {
        val s = KeyBackup.stretch(vPass)
        val dv = KeyBackup.fromSecret(s)
        val pt = KeyBackup.plaintext(vPayload)
        val ev = KeyBackup.sealWithNonce(dv, vPayload, vNonce, 1_800_000_000)
        return buildJsonObject {
            put("description", "LibreTag key backup v1 (SPEC.md 12.1). Generated by KeyBackupTest (LT_WRITE_VECTORS=1); do not edit by hand.")
            put("version", 1)
            put("scrypt", buildJsonObject {
                put("salt", KeyBackup.SALT); put("N", 65536); put("r", 8); put("p", 1); put("dkLen", 64)
            })
            put("passphrase", vPass)
            put("normalized", KeyBackup.normalize(vPass))
            put("S", s.hex())
            put("d", dv.locator)
            put("sk", dv.sk.hex())
            put("pubkey", dv.pubkeyHex)
            put("kEnc", dv.encKey.hex())
            put("nonce", vNonce.hex())
            put("plaintext", pt)
            put("content", ev.content)
            put("created_at", ev.createdAt)
            put("event_id", ev.id)
            put("filter", KeyBackup.filter(dv))
            put("deletion_a", KeyBackup.address(dv))
            put("short_passphrases", buildJsonArray {
                listOf("three word phrase", "onlythreewords butlong enough", "a b c d").forEach { add(JsonPrimitive(it)) }
            })
            put("bip39", buildBip39())
            put("bip39_invalid", buildJsonObject {
                for ((k, list) in bInvalid) put(k, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }

    /** Real cost (N = 2^16, r = 8): the PWA tests against the same file. */
    @Test fun vectorFileMatches() {
        val t0 = System.nanoTime()
        val v = buildVector()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("scrypt at real parameters took $ms ms", ms < 10_000)
        val pretty = Json { prettyPrint = true }
        if (System.getenv("LT_WRITE_VECTORS") == "1") vectorFile.writeText(pretty.encodeToString(JsonObject.serializer(), v) + "\n")
        val f = Json.parseToJsonElement(vectorFile.readText()).jsonObject
        for (k in listOf("passphrase", "normalized", "S", "d", "sk", "pubkey", "kEnc", "nonce", "plaintext", "content", "event_id", "deletion_a")) {
            assertEquals(k, f[k]!!.jsonPrimitive.content, v[k]!!.jsonPrimitive.content)
        }
        assertEquals(f["filter"], v["filter"])
        // the file alone is enough to open it
        val dv = KeyBackup.fromSecret(f["S"]!!.jsonPrimitive.content.unhex()!!)
        val ev = NostrEvent.sign(dv.sk, f["created_at"]!!.jsonPrimitive.long, 30078,
            listOf(listOf("d", dv.locator), listOf("alt", KeyBackup.ALT)), f["content"]!!.jsonPrimitive.content)
        assertEquals(f["event_id"]!!.jsonPrimitive.content, ev.id)
        assertEquals(vPayload, KeyBackup.open(dv, ev))
        assertArrayEquals(vNonce, Base64.getDecoder().decode(ev.content).copyOfRange(1, 13))
        f["short_passphrases"]!!.jsonArray.forEach {
            assertEquals(KeyBackup.Strength.TOO_SHORT, KeyBackup.strength(it.jsonPrimitive.content))
        }

        // 12.2 A
        val fb = f["bip39"]!!.jsonObject
        val vb = v["bip39"]!!.jsonObject
        for (k in listOf("input", "mnemonic", "entropy", "S", "d", "sk", "pubkey", "kEnc", "nonce", "plaintext", "content", "event_id", "deletion_a")) {
            assertEquals("bip39.$k", fb[k]!!.jsonPrimitive.content, vb[k]!!.jsonPrimitive.content)
        }
        assertEquals(fb["filter"], vb["filter"])
        assertEquals(bMnemonic, fb["mnemonic"]!!.jsonPrimitive.content)
        val db = KeyBackup.fromSecret(fb["S"]!!.jsonPrimitive.content.unhex()!!)
        val evb = NostrEvent.sign(db.sk, fb["created_at"]!!.jsonPrimitive.long, 30078,
            listOf(listOf("d", db.locator), listOf("alt", KeyBackup.ALT)), fb["content"]!!.jsonPrimitive.content)
        assertEquals(fb["event_id"]!!.jsonPrimitive.content, evb.id)
        assertEquals(bPayload, KeyBackup.open(db, evb))
        val r = KeyBackup.restore(db, listOf(evb))
        assertTrue(r is RestoreResult.Found)
        assertEquals(bPayload, (r as RestoreResult.Found).payload)
        assertEquals(f["bip39_invalid"], v["bip39_invalid"])
        val inv = f["bip39_invalid"]!!.jsonObject
        val expected = mapOf("checksum" to Bip39.Problem.BAD_CHECKSUM, "length" to Bip39.Problem.WRONG_LENGTH, "unknown_word" to Bip39.Problem.UNKNOWN_WORD)
        for ((k, problem) in expected) inv[k]!!.jsonArray.forEach {
            val c = Bip39.check(it.jsonPrimitive.content)
            assertTrue("$k: ${it.jsonPrimitive.content}", c is Bip39.Check.Bad && c.problem == problem)
            assertNull(KeyBackup.deriveFromPhrase(it.jsonPrimitive.content))
        }
    }
}
