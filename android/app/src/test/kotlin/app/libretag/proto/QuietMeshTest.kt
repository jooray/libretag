package app.libretag.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Against docs/vectors/quiet-mesh-v1.json, the contract the firmware, both bridges and the PWA share. */
class QuietMeshTest {
    private val v: JsonObject = Json.parseToJsonElement(File("../../docs/vectors/quiet-mesh-v1.json").readText()).jsonObject
    private fun hx(s: String) = LibreTag.hexToBytes(s)!!
    private fun h(o: JsonObject, k: String) = hx(o[k]!!.jsonPrimitive.content)
    private val keys = Keys(hx(v["k0"]!!.jsonPrimitive.content))
    private val ce = keys.cmd(LibreTag.epoch(300))

    @Test fun epochKeys() {
        assertArrayEquals(hx(v["view4"]!!.jsonPrimitive.content), keys.view(4))
        assertArrayEquals(hx(v["cmd4"]!!.jsonPrimitive.content), keys.cmd(4))
    }

    /** Copies 0, 1, 9, 10, 11: their x, the sealed P (v2 with the commitment), the decoder and SYNC. */
    @Test fun copies() {
        for (c in v["copies"]!!.jsonArray) {
            val o = c.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val ve = keys.view(LibreTag.epoch(n))
            val pt = h(o, "pt")
            val tags = o["tags"]!!.jsonObject
            for (j in QuietMesh.J_ON_AIR) assertEquals("n=$n j=$j", tags[j.toString()]!!.jsonPrimitive.content,
                LibreTag.bytesToHex(LibreTag.reportTag(ve, n, j)))
            assertArrayEquals(h(o, "pk"), LibreTag.mailboxPk(keys.cmd(LibreTag.epoch(n)), n))
            for (cp in o["copies"]!!.jsonArray) {
                val q = cp.jsonObject
                val j = q["j"]!!.jsonPrimitive.int
                val p = h(q, "p")
                assertArrayEquals("seal n=$n j=$j", p, keys.sealReport(n, j, pt))
                val r = LibreTag.openReport(ve, n, j, p)
                assertNotNull("open n=$n j=$j", r)
                assertTrue(r!!.sync)
                assertTrue(LibreTag.commitMatches(h(o, "pk"), p))
                // the window opens every copy on air
                val w = ReportWindow(keys, n - 1)
                val got = w.tryOpen(p)
                assertEquals(n to j, got?.let { it.first to it.second })
                // the packet's payload ends with the channel datagram carrying P (zero-hop, path or flood)
                assertEquals(p.size, 45)
            }
        }
    }

    @Test fun acks() {
        val a = v["acks"]!!.jsonObject
        for (e in a["valid"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val k = o["k"]!!.jsonPrimitive.int
            val hold = o["hold"]!!.jsonPrimitive.int
            val more = o["more"]!!.jsonPrimitive.boolean
            assertArrayEquals(h(o, "tag"), QuietMesh.ackTag(ce, n, k))
            val pa = QuietMesh.ackSeal(ce, n, k, hold, more)
            assertArrayEquals(h(o, "pa"), pa)
            val back = QuietMesh.ackOpen(ce, n, k, pa)!!
            assertEquals(hold, back.hold); assertEquals(more, back.more); assertFalse(back.routed)
        }
        for (e in a["invalid"]!!.jsonArray) {
            val o = e.jsonObject
            assertNull(o["why"]!!.jsonPrimitive.content,
                QuietMesh.ackOpen(ce, o["n"]!!.jsonPrimitive.long, o["k"]!!.jsonPrimitive.int, h(o, "datagram")))
        }
    }

    @Test fun acksRouted() {
        val a = v["acks_routed"]!!.jsonObject
        for (e in a["valid"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val k = o["k"]!!.jsonPrimitive.int
            assertArrayEquals(h(o, "pa"), QuietMesh.ackSeal(ce, n, k, 4, routed = true))
            val r = QuietMesh.ackOpen(ce, n, k, h(o, "datagram"))!!
            assertTrue(r.routed)
            assertEquals(o["hash_size"]!!.jsonPrimitive.int, r.hashSize)
            assertEquals(o["path"]!!.jsonArray.map { it.jsonPrimitive.content }, r.path)
        }
        for (e in a["invalid"]!!.jsonArray) {
            val o = e.jsonObject
            assertNull(o["why"]!!.jsonPrimitive.content,
                QuietMesh.ackOpen(ce, o["n"]!!.jsonPrimitive.long, o["k"]!!.jsonPrimitive.int, h(o, "datagram")))
        }
    }

    /** The content of ACK entries in the near, 26 B and routed forms (PROTOCOL.md 4.3). */
    @Test fun ackEntries() {
        for (e in v["ack_entries"]!!.jsonArray) {
            val o = e.jsonObject
            val form = QuietMesh.Form.entries.first { it.wire == o["form"]!!.jsonPrimitive.content }
            val got = QuietMesh.ackEntryContent(ce, o["n"]!!.jsonPrimitive.long, o["k"]!!.jsonPrimitive.int, form,
                o["hold"]!!.jsonPrimitive.int, o["more"]!!.jsonPrimitive.boolean)
            assertEquals(o["content"]!!.jsonPrimitive.content, got)
            assertEquals(if (form == QuietMesh.Form.BOTH) 26 else 13, Base64.getDecoder().decode(got).size)
        }
        assertEquals("near", QuietMesh.ackRoute(QuietMesh.Form.NEAR))
        assertEquals("direct", QuietMesh.ackRoute(QuietMesh.Form.BOTH))
        assertEquals("direct", QuietMesh.ackRoute(QuietMesh.Form.ROUTED))
        // forms by copy and the standing test (SPEC.md 3.2)
        assertEquals(QuietMesh.Form.NEAR, QuietMesh.formFor(9, standing = true))
        assertEquals(QuietMesh.Form.BOTH, QuietMesh.formFor(0, standing = true))
        assertEquals(QuietMesh.Form.BOTH, QuietMesh.formFor(10, standing = true))
        assertEquals(QuietMesh.Form.NEAR, QuietMesh.formFor(0, standing = false))
        assertEquals(QuietMesh.Form.ROUTED, QuietMesh.formFor(11, standing = false))
    }

    @Test fun slots() {
        val s = v["slots"]!!.jsonObject
        for (e in s["keys"]!!.jsonArray) {
            val o = e.jsonObject
            val nA = o["n_a"]!!.jsonPrimitive.long
            val ks = QuietMesh.slotKey(keys.cmd(LibreTag.epoch(nA)), nA)
            assertArrayEquals(h(o, "ks"), ks)
            val base = o["base_s"]!!.jsonPrimitive.long
            assertEquals(o["offsets"]!!.jsonArray.map { it.jsonPrimitive.long }, QuietMesh.slotOffsets(ks, base, 6))
            assertEquals(o["offsets_900"]!!.jsonArray.map { it.jsonPrimitive.long }, QuietMesh.slotOffsets(ks, 900, 4))
            assertEquals(o["send_at"]!!.jsonArray.map { it.jsonPrimitive.long },
                QuietMesh.slotOffsets(ks, base, 3).map { QuietMesh.slotSendAt(1763500000, it) })
        }
        val ks300 = QuietMesh.slotKey(ce, 300)
        for (e in s["commands"]!!.jsonArray) {
            val o = e.jsonObject
            val k = o["k"]!!.jsonPrimitive.int
            val cmd = Command.decode(h(o, "pt"))!!
            assertArrayEquals(h(o, "tag"), QuietMesh.slotCommandTag(ks300, k))
            assertArrayEquals(h(o, "pc"), QuietMesh.sealSlotCommand(ks300, k, cmd))
            assertArrayEquals(h(o, "pt"), QuietMesh.openSlotCommand(ks300, k, h(o, "pc"))!!.encode())
            assertNull(QuietMesh.openSlotCommand(ks300, k + 1, h(o, "pc")))
        }
        for (e in s["windows"]!!.jsonArray) {
            val o = e.jsonObject
            val off = o["offset_s"]!!.jsonPrimitive.long
            assertEquals(o["guard_ms"]!!.jsonPrimitive.long, QuietMesh.slotGuardMs(off))
            val (lo, hi) = QuietMesh.slotWindowMs(off, o["window_s"]!!.jsonPrimitive.long, o["early_s"]!!.jsonPrimitive.long)
            assertEquals(o["open_ms"]!!.jsonPrimitive.long, lo)
            assertEquals(o["close_ms"]!!.jsonPrimitive.long, hi)
        }
        for (e in s["acks_at_slot"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val k = o["k"]!!.jsonPrimitive.int
            val c = keys.cmd(LibreTag.epoch(n))
            assertArrayEquals(h(o, "tag"), QuietMesh.ackTag(c, n, k))
            assertArrayEquals(h(o, "pa"), QuietMesh.ackSeal(c, n, k, 4))
        }
    }

    /** The app's anchor (R1, R32) over the vectors' report sequences. */
    @Test fun appAnchor() {
        for (sc in v["app_anchor"]!!.jsonArray) {
            val name = sc.jsonObject["name"]!!.jsonPrimitive.content
            var st = QuietMesh.AppAnchor()
            for (step in sc.jsonObject["steps"]!!.jsonArray) {
                val o = step.jsonObject
                val ev = o["event"]!!.jsonArray
                st = if (ev[0].jsonPrimitive.content == "confirmed") st.onCommandConfirmed(ev[1].jsonPrimitive.long)
                else st.onReport(ev[1].jsonPrimitive.long, ev[2].jsonPrimitive.int, ev[3].jsonPrimitive.long,
                    ev[4].jsonPrimitive.boolean, ev[5].jsonPrimitive.boolean,
                    ev.getOrNull(6)?.jsonPrimitive?.boolean ?: true, ev.getOrNull(7)?.jsonPrimitive?.boolean ?: false)
                fun grid(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonArray.let { QuietMesh.Grid(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long) }
                assertEquals("$name $ev a", grid(o["a"]), st.a)
                assertEquals("$name $ev cands", (o["cands"] as JsonArray).map { grid(it) }, st.cands)
                assertEquals("$name $ev known", o["known"]!!.jsonPrimitive.boolean, st.known)
            }
        }
    }

    @Test fun hold() {
        for (e in v["hold"]!!.jsonArray) {
            val a = e.jsonArray
            assertEquals(a.toString(), a[2].jsonPrimitive.longOrNull, QuietMesh.holdS(a[0].jsonPrimitive.int, a[1].jsonPrimitive.long))
        }
    }

    @Test fun fallbackOverGrids() {
        assertEquals(listOf(false, true), QuietMesh.pairFallback(listOf(100L, 200L), lostOrTrack = false))
        assertEquals(listOf(true, true), QuietMesh.pairFallback(listOf(100L, 200L), lostOrTrack = true))
        assertEquals(listOf(true), QuietMesh.pairFallback(listOf(100L), lostOrTrack = false))
    }

    /** Next 3 slots, then every 3rd; LOST every slot for its first 6 h (PROTOCOL.md 4). */
    @Test fun commandSlots() {
        val offs = (1..30).map { it * 3600L }
        assertEquals(listOf(1, 2, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30), QuietMesh.commandSlots(offs, 1, 200 * 3600L, lost = false))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 9, 12, 15, 18, 21, 24), QuietMesh.commandSlots(offs, 1, 200 * 3600L, lost = true))
        // the lifetime ends at 4 h: slots 1..3, and slot 4 is not every 3rd
        assertEquals(listOf(1, 2, 3), QuietMesh.commandSlots(offs, 1, 4 * 3600L, lost = false))
        assertEquals(listOf(5, 6, 7, 10), QuietMesh.commandSlots(offs, 5, 11 * 3600L, lost = false))
    }

    @Test fun setIdleAckPatienceAndSetArea() {
        val c = Command.setIdle(7, 60, 0, 30, 3, ackPatience = 2)
        assertEquals(2, Command.idleAckPatience(c))
        assertNull(Command.idleAckPatience(Command.setIdle(7, 60, 0, 30, 3)))
        // with offset 9 set the command has no Bluetooth form (PROTOCOL.md 3)
        assertNull(BleCommand.payload(ce, 300, c, BleCommand.K_NEXT))
        val a = Command.setArea(8, 1, 48.70, 21.25, 12, "sktest")
        assertEquals(Command.OP_SET_AREA, a.op)
        assertEquals(1, a.args[0].toInt())
        assertEquals(12, a.args[7].toInt())
        assertEquals("sktest", String(a.args.copyOfRange(8, 14), Charsets.US_ASCII))
        assertEquals(0x81, Command.setArea(8, 1, 0.0, 0.0, 0, "", delete = true).args[0].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class) fun setAreaScopeTooLong() {
        Command.setArea(8, 1, 48.70, 21.25, 12, "sk-test7")
    }

    /** the refusals of the firmware's lt_set_area_decode (qm_test.c), loud in the app */
    @Test fun setAreaRefusesWhatTheFirmwareRefuses() {
        fun refused(f: () -> Unit) = try { f(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(refused { Command.setArea(8, 4, 48.70, 21.25, 12, "sktest") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 21.25, 0, "sktest") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 21.25, 256, "sktest") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 21.25, 12, "") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 21.25, 12, "sKtest") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 21.25, 12, "sk.ke") })
        assertTrue(refused { Command.setArea(8, 1, 90.0, 21.25, 12, "sktest") })
        assertTrue(refused { Command.setArea(8, 1, 48.70, 180.0, 12, "sktest") })
        assertEquals(12, Command.setArea(8, 1, 48.70, 21.25, 12, "sk_k-1").args[7].toInt())
    }
}
