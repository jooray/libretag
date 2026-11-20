package app.libretag.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerStatusTest {
    // what firmware `status` prints (main_tracker.cpp print_status / print_nostr), echo included
    private val out = """
        status
        LIBRETAG KEY 00112233445566778899AABBCCDDEEFF00112233445566778899aabbccddeeff
        n=17 mode=0 interval=60min listen=180s fixto=90s transport=channel
        radio 869.618 MHz bw=62.5 sf=7 cr=5 pwr=22 dBm path=2B
        network auto (GPS): country SK, region sk
        battery 87% (4012 mV) phase=0 gps=yes last_fix=yes 48.700000,21.250000
        NOSTR NPUB npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0v8ycx
        owner none (DMs off): owner <npub|hex>
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun parsesStatus() {
        val s = TrackerStatus.parse(out.split('\n').map { it.trim('\r') })
        assertEquals("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff", s.keyHex)
        assertEquals(87, s.battery)
        assertEquals(60, s.intervalMin)
        assertEquals("channel", s.transport)
        assertEquals("48.700000, 21.250000", s.lastFix)
        assertEquals("auto (GPS): country SK, region sk", s.network)
        assertEquals("869.618 MHz bw=62.5 sf=7 cr=5 pwr=22 dBm path=2B", s.radio)
        assertEquals("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0v8ycx", s.npub)
        assertNull(s.owner)
    }

    @Test
    fun noKeyWhenNotATracker() {
        assertNull(TrackerStatus.parse(listOf("ERR unknown command", "> ")).keyHex)
    }

    // firmware 0.9.1 `status` with the txcap line (and the same line before 0.9.1, without last_reset)
    private val txcap = listOf(
        "n=1284 mode=0 interval=60min listen=180s fixto=90s transport=channel check=30s",
        "txcap floods_24h=12 cap=200 over=no low_alert=armed resets=4 last_reset=brownout boot_delay=960s tx_floor=3250mV tx=ok",
    )

    @Test
    fun parsesTheTxcapLine() {
        val s = TrackerStatus.parse(txcap)
        assertEquals(1284L, s.n)
        assertEquals(4, s.resets)
        assertEquals("brownout", s.lastReset)
        assertEquals(960, s.bootDelayS)
        assertEquals(true, s.txOk)
        val old = TrackerStatus.parse(listOf("txcap floods_24h=0 cap=200 over=no low_alert=armed resets=0 boot_delay=30s tx_floor=3250mV tx=low_voltage"))
        assertNull(old.lastReset)
        assertEquals(false, old.txOk)
        assertEquals(0, old.resets)
        val h = TrackerStatus.health(s, "owner", 1000L)
        assertEquals("owner", h.quiet)
        assertEquals(60, h.intervalMin)
        assertEquals(true, h.faultReset)
    }

    @Test
    fun parsesQuiet() {
        assertEquals("owner", TrackerStatus.parseQuiet(listOf("quiet", "QUIET owner since 1200, last report 1200 ago, next report in 27600")))
        assertEquals("none", TrackerStatus.parseQuiet(listOf("QUIET none, next report in 1800")))
        assertNull(TrackerStatus.parseQuiet(listOf("ERR unknown command")))
    }
}
