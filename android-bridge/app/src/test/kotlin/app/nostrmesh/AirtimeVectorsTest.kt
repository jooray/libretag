package app.nostrmesh

import app.nostrmesh.bridge.Airtime
import app.nostrmesh.bridge.LoRaPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/vectors/lora-airtime-v1.json: the same numbers as the host bridge's lora_airtime_s and grp_data_len. */
class AirtimeVectorsTest {
    private val vec = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lora-airtime-v1.json")!!.readText()).jsonObject

    @Test
    fun timeOnAirWithinOnePercent() {
        val cases = vec["airtime"]!!.jsonArray
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val o = c.jsonObject
            val p = LoRaPreset(o["sf"]!!.jsonPrimitive.int, o["bw_hz"]!!.jsonPrimitive.double, o["cr"]!!.jsonPrimitive.int)
            val want = o["ms"]!!.jsonPrimitive.double
            val got = Airtime.ms(o["bytes"]!!.jsonPrimitive.int, p, o["preamble"]!!.jsonPrimitive.int)
            assertEquals("$o", want, got, want * 0.01)
        }
    }

    @Test
    fun channelPacketBytes() {
        for (c in vec["channel_packet"]!!.jsonArray) {
            val o = c.jsonObject
            val got = Airtime.channelPacketBytes(o["datagram"]!!.jsonPrimitive.int, o["path_bytes"]!!.jsonPrimitive.int,
                o["region"]!!.jsonPrimitive.boolean)
            assertEquals("$o", o["bytes"]!!.jsonPrimitive.int, got)
        }
    }
}
