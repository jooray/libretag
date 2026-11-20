package app.libretag.proto

import app.libretag.data.WifiApRec
import app.libretag.data.WifiMerge
import app.libretag.data.WifiRec
import app.libretag.wifi.BeaconDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Wi-Fi layout without a fix time: a second full BSSID in bytes 8..14 (PROTOCOL.md 2.2, vectors `wifi_nofix`). */
class WifiNoFixTest {
    private val v: JsonObject = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
    private fun h(o: JsonObject, k: String) = LibreTag.hexToBytes(o[k]!!.jsonPrimitive.content)!!
    private val keys = Keys(LibreTag.hexToBytes(v["k0"]!!.jsonPrimitive.content)!!)

    @Test fun decodesSecondSeed() {
        for (e in v["wifi_nofix"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.int.toLong()
            val r = LibreTag.openReport(keys.view(LibreTag.epoch(n)), n, 0, h(o, "p"))
            assertNotNull(r)
            r!!
            assertEquals(0L, r.fixTime)
            assertNull("fix time 0: no last fix", r.lastFix)
            assertNull("byte 14 is the second seed's RSSI, not an accuracy", r.haccM)
            assertEquals(0.0, r.lat, 0.0)
            val w = r.wifi!!
            assertEquals(o["seed"]!!.jsonPrimitive.content, w.seedHex)
            assertEquals(o["seed2"]!!.jsonPrimitive.content, w.seed2Hex)
            assertTrue(abs(w.seed2Rssi - o["seed2_rssi"]!!.jsonPrimitive.int) <= 1) // 2 dB steps
            assertEquals(o["tail"]!!.jsonPrimitive.content, w.tailHex)
            // the second tail sits in the repeater's slot: no repeater entry, the count stays
            assertEquals(o["tail2"]!!.jsonPrimitive.content, w.tail2Hex)
            assertTrue(abs(w.tail2Rssi - o["tail2_rssi"]!!.jsonPrimitive.int) <= 1)
            assertTrue(r.neighbours.isEmpty())
            assertEquals(2, r.nbTotal)
            assertEquals(2, WifiMerge.observation(WifiMerge.ofReport(ReportExtras.of(r)))!!.tails.size)
            // the plaintext decodes the same
            assertEquals(r, Report.decode(h(o, "pt")))

            // into the store and on to a lookup: two full BSSIDs, enough for BeaconDB
            val rec = WifiMerge.ofReport(ReportExtras.of(r))!!
            assertEquals(listOf(o["seed2"]!!.jsonPrimitive.content), rec.others.map { it.mac })
            val obs = WifiMerge.observation(rec)!!
            assertEquals(2, obs.fullMacs.size)
            assertTrue(obs.fullMacs.size >= BeaconDb.MIN_APS)
            assertEquals("44:d9:e7:00:00:02", obs.others[0].mac)
        }
    }

    @Test fun bothTailsMatchTheServiceNeighbours() {
        val o = v["wifi_nofix"]!!.jsonArray[0].jsonObject
        val r = Report.decode(h(o, "pt"))!!
        val obs = WifiMerge.observation(WifiMerge.ofReport(ReportExtras.of(r)))!!
        // what a full-BSSID query returns: the seeds and their neighbours (synthetic, 48.70 / 21.25)
        val known = listOf(
            app.libretag.wifi.ApPosition("00:24:a5:11:22:33", 48.7000, 21.2500, 30.0),
            app.libretag.wifi.ApPosition("44:d9:e7:00:00:02", 48.7003, 21.2504, 30.0),
            app.libretag.wifi.ApPosition("02:aa:bb:00:00:01", 48.7001, 21.2502, 40.0),
            app.libretag.wifi.ApPosition("c8:d7:19:00:00:03", 48.7002, 21.2497, 40.0),
        )
        val m = app.libretag.wifi.TailMatcher.match(obs, known)
        assertEquals(4, m.matched.size) // two full BSSIDs and both tails
        assertTrue(m.matched.all { it.rssiDbm != null })
        assertNotNull(app.libretag.wifi.WifiEstimator.fromObservation(obs, app.libretag.wifi.LookupResult(aps = known)))
    }

    @Test fun fewApsKeepTheRepeater() {
        val o = v["wifi_few"]!!.jsonObject
        val n = o["n"]!!.jsonPrimitive.int.toLong()
        val r = LibreTag.openReport(keys.view(LibreTag.epoch(n)), n, 0, h(o, "p"))!!
        assertNull(r.wifi!!.tail2)
        assertEquals(o["tail"]!!.jsonPrimitive.content, r.wifi!!.tailHex)
        assertEquals(listOf("a1b2"), r.neighbours.map { it.hash })
    }

    @Test fun fixTimeMeansPosition() {
        val o = v["wifi_nofix"]!!.jsonArray[0].jsonObject
        val pt = h(o, "pt")
        pt[4] = 1 // a fix time: bytes 8..14 are the last fix again
        val r = Report.decode(pt)!!
        assertNull(r.wifi!!.seed2)
        assertNotNull(r.lastFix)
        assertEquals(1, WifiMerge.observation(WifiMerge.ofReport(ReportExtras.of(r)))!!.fullMacs.size)
    }

    @Test fun wifiPacketKeepsTheReportSeed() {
        val cur = WifiRec("0024a5112233", -60, 4, emptyList(), listOf(WifiApRec("44d9e7000002", -66)))
        val pkt = WifiPacket(8, LibreTag.hexToBytes("44d9e7000009")!!, -70, listOf(LibreTag.hexToBytes("000001")!! to -50))
        val m = WifiMerge.withPacket(cur, pkt)!!
        assertEquals("0024a5112233", m.seed)
        assertEquals(listOf("44d9e7000002", "44d9e7000009"), m.others.map { it.mac })
        assertEquals(listOf("000001"), m.tails.map { it.tail })
        assertEquals(8, m.apCount)
        // a packet without a report: its seed becomes the seed; an empty packet adds nothing
        assertEquals("44d9e7000009", WifiMerge.withPacket(null, pkt)!!.seed)
        assertNull(WifiMerge.withPacket(null, WifiPacket(0, ByteArray(6), -95, emptyList())))
    }
}
