package app.libretag.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The console over Bluetooth against the `ble_console` vectors (Python reference, C core). */
class BleConsoleTest {
    private val all: JsonObject = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
    private val v = all["ble_console"]!!.jsonObject
    private fun h(o: JsonObject, k: String) = LibreTag.hexToBytes(o[k]!!.jsonPrimitive.content)!!
    private val usbKey = Keys(LibreTag.hexToBytes(all["k0"]!!.jsonPrimitive.content)!!).usbKey
    private val salt = h(v, "salt")
    private val tn = h(v, "tag_nonce")
    private val an = h(v, "app_nonce")

    @Test fun advert() {
        for (e in v["adv"]!!.jsonArray) {
            val o = e.jsonObject
            val p = BleConsole.adv(usbKey, salt, o["left_s"]!!.jsonPrimitive.int)
            assertArrayEquals(h(o, "payload"), p)
            assertTrue(BleConsole.advMatch(usbKey, p))
            assertTrue(BleConsole.isConsoleAdvert(p))
            assertFalse(BleConsole.advMatch(ByteArray(32), p))
            val bad = p.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
            assertFalse(BleConsole.advMatch(usbKey, bad))
            assertFalse(BleConsole.advMatch(usbKey, p.copyOf(13)))
        }
        assertEquals(360, BleConsole.leftSeconds(BleConsole.adv(usbKey, salt, 359)))
        assertEquals(510, BleConsole.leftSeconds(BleConsole.adv(usbKey, salt, 600)))
    }

    @Test fun matchAmongSeveralTags() {
        val keys = (1..5).map { Keys(ByteArray(32) { b -> (b * it + 100).toByte() }).usbKey } + listOf(usbKey)
        val p = h(v["adv"]!!.jsonArray[0].jsonObject, "payload")
        assertEquals(listOf(5), keys.indices.filter { BleConsole.advMatch(keys[it], p) })
    }

    @Test fun handshake() {
        assertArrayEquals(h(v, "proof_a"), BleConsole.proof(usbKey, 'a', tn, an))
        assertArrayEquals(h(v, "proof_t"), BleConsole.proof(usbKey, 't', tn, an))
        val hello = byteArrayOf(BleConsole.HELLO.toByte(), BleConsole.VER.toByte()) + tn
        assertArrayEquals(tn, BleConsole.helloNonce(hello))
        assertNull(BleConsole.helloNonce(hello.copyOf(17)))
        val auth = BleConsole.auth(usbKey, tn, an)
        assertEquals(33, auth.size)
        assertArrayEquals(h(v, "proof_a"), auth.copyOfRange(17, 33))
        val ready = byteArrayOf(BleConsole.READY.toByte()) + h(v, "proof_t")
        assertTrue(BleConsole.readyOk(usbKey, ready, tn, an))
        assertFalse(BleConsole.readyOk(ByteArray(32), ready, tn, an))
        assertFalse(BleConsole.readyOk(usbKey, byteArrayOf(BleConsole.READY.toByte()) + h(v, "proof_a"), tn, an))
        val s = BleConsole.Session(usbKey, tn, an)
        assertArrayEquals(h(v, "kd_a"), s.kd[0])
        assertArrayEquals(h(v, "kd_t"), s.kd[1])
    }

    @Test fun framesAndRejects() {
        for (e in v["frames"]!!.jsonArray) {
            val o = e.jsonObject
            val d = o["dir"]!!.jsonPrimitive.int
            val c = o["c"]!!.jsonPrimitive.long
            val pt = h(o, "pt")
            val f = h(o, "frame")
            val s = BleConsole.Session(usbKey, tn, an)
            s.ctr[d] = c
            assertArrayEquals(f, s.seal(d, pt))
            assertEquals((c + 1) and 0xFFFFFFFFL, s.ctr[d])
            val r = BleConsole.Session(usbKey, tn, an)
            r.ctr[d] = c
            assertArrayEquals(pt, r.open(d, f))
            // wrong counter, a bit in ct, a bit in mac, the other direction, truncated, wrong type
            val rejects = listOf(
                Triple((c + 1) and 0xFFFFFFFFL, f, d),
                Triple(c, f.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }, d),
                Triple(c, f.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }, d),
                Triple(c, f, 1 - d),
                Triple(c, f.copyOf(f.size - 1), d),
                Triple(c, f.copyOf(9), d),
                Triple(c, f.copyOf().also { it[0] = 0x11 }, d),
            )
            for ((ctr, frame, dd) in rejects) {
                val x = BleConsole.Session(usbKey, tn, an)
                x.ctr[dd] = ctr
                assertNull(x.open(dd, frame))
                assertEquals(ctr, x.ctr[dd])
            }
        }
    }

    @Test fun chunking() {
        assertEquals(11, BleConsole.chunk(23))
        assertEquals(52, BleConsole.chunk(64))
        assertEquals(235, BleConsole.chunk(247))
        assertEquals(235, BleConsole.chunk(517))
        val text = ByteArray(600) { (it % 251).toByte() }
        for (mtu in listOf(23, 64, 247)) {
            val a = BleConsole.Session(usbKey, tn, an)
            val frames = a.frames(text, mtu)
            assertTrue(frames.all { it.size <= mtu - 3 })
            val b = BleConsole.Session(usbKey, tn, an)
            val back = frames.map { b.open(BleConsole.A2T, it)!! }.reduce { x, y -> x + y }
            assertArrayEquals(text, back)
        }
    }

    /** The wake advert (firmware 0.9.5) from fixed bytes, checked against HMAC-SHA256 computed here. */
    @Test fun wakeAdvert() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val wakeSalt = byteArrayOf(0x10, 0x21, 0x32, 0x43, 0x54, 0x65)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
            .doFinal("LT2-bcon-wake".toByteArray() + wakeSalt)
        val p = BleConsole.wakePayload(key, wakeSalt)
        assertEquals(13, p.size)
        assertEquals(0x26, p[0].toInt())
        assertArrayEquals(wakeSalt, p.copyOfRange(1, 7))
        assertArrayEquals(mac.copyOf(6), p.copyOfRange(7, 13))
        assertTrue(BleConsole.wakeMatch(key, p))
        assertFalse(BleConsole.wakeMatch(usbKey, p))
        assertFalse(BleConsole.wakeMatch(key, p.copyOf().also { it[2] = (it[2].toInt() xor 1).toByte() }))
        // not a console advert: the phone's own scan ignores it
        assertFalse(BleConsole.isConsoleAdvert(p))
        val ad = BleConsole.wakeAd(key, wakeSalt)
        assertEquals("10ffffff", LibreTag.bytesToHex(ad.copyOf(4)))
        assertArrayEquals(p, ad.copyOfRange(4, ad.size))
        assertEquals(17, ad.size)
    }

    /** The wake advert against the shared `bcon_wake` vectors (Python reference, C core), usb_key of k0. */
    @Test fun wakeVectors() {
        val w = all["bcon_wake"]!!.jsonObject
        for (e in w["valid"]!!.jsonArray) {
            val o = e.jsonObject
            val s = h(o, "salt")
            assertArrayEquals(h(o, "payload"), BleConsole.wakePayload(usbKey, s))
            assertArrayEquals(h(o, "ad"), BleConsole.wakeAd(usbKey, s))
            assertTrue(BleConsole.wakeMatch(usbKey, h(o, "payload")))
        }
        for (e in w["invalid"]!!.jsonArray) assertFalse(BleConsole.wakeMatch(usbKey, h(e.jsonObject, "payload")))
    }
}
