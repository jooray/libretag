package app.nostrmesh

import app.nostrmesh.bridge.Airtime
import app.nostrmesh.bridge.AirtimeBudget
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.LoRaPreset
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.bridge.downlinkRoute
import app.nostrmesh.bridge.lt2CommandRoute
import app.nostrmesh.mesh.meta
import app.nostrmesh.mesh.toApi
import app.nostrmesh.mesh.toPreset
import io.github.jooray.meshcore.localapi.Hello
import io.github.jooray.meshcore.localapi.MeshEvent
import io.github.jooray.meshcore.localapi.RadioParams
import io.github.jooray.meshcore.localapi.Route as ApiRoute
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun loraAirtimeMatchesSemtech() {
        // Semtech calculator: SF7, 125 kHz, 4/5, 20 B, preamble 8, explicit header, CRC: 56.58 ms
        assertEquals(56.58, Airtime.ms(20, LoRaPreset(7, 125_000.0, 5), preamble = 8), 0.01)
        // SF12 / 125 kHz uses low data rate optimisation: 1318.91 ms for 20 B (Semtech)
        assertEquals(1318.91, Airtime.ms(20, LoRaPreset(12, 125_000.0, 5), preamble = 8), 0.1)
        // LT2 report packet on #libretag: 51 B payload + header + path_len = 53 B (review table)
        assertEquals(53, Airtime.channelPacketBytes(45))
        assertEquals(37, Airtime.channelPacketBytes(29))
        assertEquals(41, Airtime.channelPacketBytes(29, region = true))
    }

    @Test
    fun budgetRollsOverOneHour() {
        val b = AirtimeBudget(0.001) // 3600 ms per hour
        assertTrue(b.spend(0, 2000.0))
        assertTrue(b.spend(10, 1500.0))
        assertFalse(b.spend(20, 200.0))
        assertEquals(3500.0, b.usedMs(20), 0.0)
        assertTrue("first spend left the window", b.spend(3600_000, 2000.0))
        val c = AirtimeBudget(0.001)
        c.restore(b.export(), 3600_011)
        assertEquals(2000.0, c.usedMs(3600_011), 0.0)
    }

    @Test
    fun presetParse() {
        assertEquals(LoRaPreset(7, 62_500.0, 5), LoRaPreset.parse("7/62.5/5"))
        assertEquals(LoRaPreset(8, 62_500.0, 8), LoRaPreset.parse("8/62.5/4"))
        assertNull(LoRaPreset.parse("13/62.5/5"))
        assertEquals(LoRaPreset.SK, LoRaPreset.parse(LoRaPreset.SK.toString()))
    }

    @Test
    fun routeChoice() {
        val all = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
        assertEquals(Route.ZeroHop, downlinkRoute(RxMeta(path = ByteArray(0), pathHashSize = 1, routeType = "direct"), all, false))
        assertEquals(Route.ZeroHop, downlinkRoute(RxMeta(hops = 0), setOf(Caps.TX_PATH_DATA), false))
        assertNull(downlinkRoute(RxMeta(hops = 0), emptySet(), false))
        assertNull(downlinkRoute(RxMeta(hops = 2, path = "0102".unhex()), setOf(Caps.TX_PATH_DATA), false))
        assertEquals("0201", (downlinkRoute(RxMeta(hops = 2, path = "0102".unhex(), pathHashSize = 1), all, false) as Route.Direct).path.hex())
        assertNull(downlinkRoute(RxMeta(hops = 4, path = "01020304".unhex(), pathHashSize = 1), all, false))
        assertEquals(Route.Flood, downlinkRoute(RxMeta(), emptySet(), true))
        // route=flood heard directly: zero-hop is enough (lt2svc.py plan table)
        assertEquals(Route.ZeroHop, downlinkRoute(RxMeta(hops = 0), setOf(Caps.TX_PATH_DATA), true))
        assertEquals(Route.Flood, downlinkRoute(RxMeta(hops = 0), emptySet(), true))
        assertEquals(Route.Flood, downlinkRoute(RxMeta(hops = 2, path = "0102".unhex(), pathHashSize = 1), all, true))
    }

    @Test
    fun lt2CommandRouteZeroHopOrFlood() {
        val tx = setOf(Caps.TX_PATH_DATA)
        assertEquals(Route.ZeroHop, lt2CommandRoute(0, tx))
        assertEquals(Route.Flood, lt2CommandRoute(0, emptySet()))
        for (h in listOf(1, 3, 4, 16, 40, null)) assertEquals(Route.Flood, lt2CommandRoute(h, tx))
    }

    @Test
    fun helloCapabilities() {
        assertEquals(setOf("tx_path_data", "rx_path", "airtime"),
            Hello.parse(obj("""{"type":"hello","capabilities":["tx_path_data","rx_path","airtime",3]}""")).capabilities)
        assertEquals(emptySet<String>(), Hello.parse(obj("""{"type":"hello","device":{"capabilities":["rx_path"]}}""")).capabilities)
        assertEquals(emptySet<String>(), Hello.parse(obj("""{"type":"hello","capabilities":"rx_path"}""")).capabilities)
    }

    @Test
    fun channelDataMeta() {
        val ev = obj("""{"type":"channel.data","channel":"#libretag","dataType":65356,"payload":"AAEC","snrDb":7.25,
            "rssiDbm":-97,"hops":2,"path":"a1a2b1b2","pathHashSize":2,"routeType":"flood","receivedAt":"2026-11-16T10:00:00.000Z"}""")
        val d = MeshEvent.parse(ev) as MeshEvent.ChannelData
        val m = d.meta()
        assertEquals("a1a2b1b2", m.path!!.hex())
        assertEquals(2, m.pathHashSize)
        assertEquals("flood", m.routeType)
        assertEquals(-97.0, m.rssiDbm!!, 0.0)
        assertEquals(7.25, m.snrDb!!, 0.0)
        assertEquals(1_794_823_200_000L, m.receivedAtMs)
        assertEquals(2, m.hopCount(setOf(Caps.RX_PATH)))
        // a server without rx_path / rx_meta: no extension fields
        val old = (MeshEvent.parse(obj("""{"type":"channel.data","channel":"#nostr","dataType":65358,"payload":"AA==","hops":0}""")) as MeshEvent.ChannelData).meta()
        assertNull(old.path); assertNull(old.routeType)
        assertEquals(0, old.hopCount(emptySet()))
    }

    @Test
    fun routeMapsToLibrary() {
        assertEquals(ApiRoute.Flood, Route.Flood.toApi())
        assertEquals(ApiRoute.ZeroHop, Route.ZeroHop.toApi())
        val d = Route.Direct("ccddaabb".unhex()!!, 2).toApi() as ApiRoute.Direct
        assertEquals("ccddaabb", d.path.hex())
        assertEquals(2, d.hashSize)
        assertEquals(2, d.hops)
    }

    @Test
    fun capsAreTheSpecNames() {
        assertEquals("tx_path_data", Caps.TX_PATH_DATA)
        assertEquals("rx_path", Caps.RX_PATH)
        assertEquals("radio_stats", Caps.RADIO_STATS)
    }

    @Test
    fun radioStatsPreset() {
        val r = obj("""{"preset":{"freqHz":869618000,"bwHz":62500,"sf":7,"cr":5,"txPowerDbm":22}}""")
        assertEquals(LoRaPreset(7, 62_500.0, 5), RadioParams.parse(r["preset"]!!.jsonObject)!!.toPreset())
        assertEquals(LoRaPreset(8, 62_500.0, 8), RadioParams.parse(obj("""{"bwHz":62500,"sf":8,"cr":4}"""))!!.toPreset())
        assertNull(RadioParams.parse(obj("""{"bwHz":62500,"sf":13,"cr":5}""")))
    }
}
