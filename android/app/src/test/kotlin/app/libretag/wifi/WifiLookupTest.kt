package app.libretag.wifi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToLong

class WifiLookupTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun ascii(s: String) = hex(s.toByteArray())

    @Test fun macNormalisation() {
        assertEquals("02:00:00:0a:0b:01", Mac.normalize("2:0:0:a:B:1"))
        assertEquals("02:00:00:0a:0b:01", Mac.normalize("02-00-00-0A-0B-01"))
        assertEquals("02:00:00:0a:0b:01", Mac.normalize("0200000a0b01"))
        assertNull(Mac.normalize("02:00:00:0a:0b"))
        assertNull(Mac.normalize("02:00:00:0a:0b:0g"))
        assertNull(Mac.normalize("02:00:00:0a:0b:100"))
        assertEquals("0a:0b:01", Mac.normalizeTail("A:B:1"))
        assertEquals("0b:0c:0d", Mac.tail("02:00:00:0b:0c:0d"))
        assertTrue(Mac.isLocallyAdministered("02:00:00:00:00:01"))
        assertFalse(Mac.isLocallyAdministered("00:1a:2b:00:00:01"))
    }

    @Test fun protobufRoundTrip() {
        val inner = Protobuf.Writer().string(1, "x").int(2, -5)
        val b = Protobuf.Writer().int(1, 300).int(2, -18_000_000_000L).message(3, inner).packed(4, listOf(1, 2, 300)).toByteArray()
        assertEquals("08ac02", hex(b.copyOf(3)))
        val f = Protobuf.fields(b)
        assertEquals(listOf(1, 2, 3, 4), f.map { it.number })
        assertEquals(300L, f[0].varint)
        assertEquals(-18_000_000_000L, f[1].varint)
        val g = Protobuf.fields(f[2].bytes!!)
        assertEquals("x", g[0].string())
        assertEquals(-5L, g[1].varint)
        assertEquals("0102ac02", hex(f[3].bytes!!))
        try {
            Protobuf.fields(byteArrayOf(0x0a, 0x05, 0x01))
            error("truncated input accepted")
        } catch (e: IOException) { /* expected */ }
    }

    @Test fun appleRequestBodyExact() {
        val body = AppleWloc.requestBody(listOf("2:0:0:0:0:1"))
        val proto = "12" + "13" + "0a" + "11" + ascii("02:00:00:00:00:01") +
            "20" + "64" + // 4: 100 surrounding
            "fa01" + "02" + "0102" + // 31: packed [1, 2]
            "8002" + "02" + // 32: 2
            "8a02" + "19" + "0a10" + ascii("macOS15.4/24E248") + "1205" + ascii("arm64") // 33: meta
        val expected = "0001" + "0008" + ascii("en-US_US") + "0013" + ascii("com.apple.locationd") +
            "000b" + ascii("15.4.24E248") + "00000001" + "%08x".format(proto.length / 2) + proto
        assertEquals(expected, hex(body))
        // more MACs: one wireless_aps entry each, duplicates and garbage dropped, at most 40
        val many = AppleWloc.requestProto((1..50).map { "02:00:00:00:00:%02x".format(it) })
        assertEquals(40, Protobuf.fields(many).count { it.number == 2 })
        val dup = AppleWloc.requestBody(listOf("02:00:00:00:00:01", "2:0:0:0:0:1", "nonsense"))
        assertArrayEquals(body, dup)
    }

    /** A fake ALSLocationResponse as Apple sends it: 10 header bytes, short MACs, unknowns. */
    private fun appleResponse(aps: List<Triple<String, Pair<Double, Double>?, Int>>): ByteArray {
        val w = Protobuf.Writer()
        for ((mac, pos, acc) in aps) {
            val loc = Protobuf.Writer()
            if (pos == null) {
                loc.int(1, AppleWloc.UNKNOWN_LAT).int(2, AppleWloc.UNKNOWN_LAT).int(3, -1)
            } else {
                loc.int(1, (pos.first * 1e8).roundToLong()).int(2, (pos.second * 1e8).roundToLong()).int(3, acc.toLong())
                    .int(4, 1).int(5, -100) // fields the parser ignores
            }
            w.message(2, Protobuf.Writer().string(1, mac).message(2, loc).int(21, 6))
        }
        val out = ByteArrayOutputStream()
        out.write(ByteArray(10) { 0x7f })
        out.write(w.toByteArray())
        return out.toByteArray()
    }

    @Test fun appleResponseParsed() {
        val body = appleResponse(listOf(
            Triple("2:0:0:a:b:1", 48.701234 to 21.251234, 25),
            Triple("2:0:0:a:b:2", null, 0),
            Triple("2:0:0:a:b:3", -48.70 to -21.25, 40),
            Triple("2:0:0:a:b:1", 10.0 to 10.0, 5), // duplicate: first one wins
        ))
        val r = AppleWloc.parseResponse(body)
        assertEquals(2, r.aps.size)
        assertEquals("02:00:00:0a:0b:01", r.aps[0].mac)
        assertEquals(48.701234, r.aps[0].lat, 1e-9)
        assertEquals(21.251234, r.aps[0].lon, 1e-9)
        assertEquals(25.0, r.aps[0].accuracyM, 0.0)
        assertEquals(-21.25, r.aps[1].lon, 1e-9)
        assertEquals(listOf("02:00:00:0a:0b:02"), r.unknown)
        assertEquals(0, AppleWloc.parseResponse(ByteArray(10)).aps.size)
        try {
            AppleWloc.parseResponse(ByteArray(4))
            error("short body accepted")
        } catch (e: IOException) { /* expected */ }
    }

    @Test fun beaconDb() {
        val j = Json.parseToJsonElement(BeaconDb.requestJson(listOf(WifiAp("2:0:0:0:0:1", -60), WifiAp("02:00:00:00:00:02")))).jsonObject
        assertFalse(j["considerIp"]!!.jsonPrimitive.boolean)
        val aps = j["wifiAccessPoints"]!!.jsonArray
        assertEquals("02:00:00:00:00:01", aps[0].jsonObject["macAddress"]!!.jsonPrimitive.content)
        assertEquals(-60, aps[0].jsonObject["signalStrength"]!!.jsonPrimitive.int)
        assertNull(aps[1].jsonObject["signalStrength"])
        assertTrue(runCatching { BeaconDb.requestJson(listOf(WifiAp("02:00:00:00:00:01"), WifiAp("2:0:0:0:0:1"))) }.isFailure)

        assertEquals(DirectFix(48.7, 21.25, 80.0), BeaconDb.parseResponse("""{"location":{"lat":48.7,"lng":21.25},"accuracy":80}"""))
        assertNull(BeaconDb.parseResponse("""{"error":{"code":404,"message":"Not found"}}"""))
        assertNull(BeaconDb.parseResponse("<html>"))
    }

    @Test fun servicesAndObservation() {
        assertNull(WifiService.OFF.endpoint)
        assertTrue(WifiService.GRAPHENEOS_PROXY.isApple && WifiService.APPLE.isApple && !WifiService.BEACONDB.isApple)
        val o = WifiObservation(WifiAp("02:00:00:00:00:01"), listOf(WifiTail("00:00:02")), listOf(WifiAp("02:00:00:00:00:03")))
        assertEquals(listOf("02:00:00:00:00:01", "02:00:00:00:00:03"), o.fullMacs.map { it.mac })
    }
}
