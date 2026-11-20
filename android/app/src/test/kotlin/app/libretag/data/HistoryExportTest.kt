package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {
    private fun pos(n: Long, fix: Boolean, lat: Double = 48.70, lon: Double = 21.25) = Position(
        fixTime = if (fix) 1_800_000_000L + n * 3600 else 0, receivedAt = (1_800_000_000L + n * 3600 + 20) * 1000,
        hasFix = fix, lat = if (fix) lat else 0.0, lon = if (fix) lon else 0.0, altM = 0, battery = 80, mode = "IDLE",
        hops = 2, snrDb = 0.0, via = "nostr", n = n, haccM = if (fix) 8 else null,
        est = if (!fix) EstimateRec(48.71, 21.26, 900.0, 2100.0, "ok") else null)

    private val t = Tracker(name = "Bike <&> \"1\"", keyHex = "", positions = listOf(pos(1, true), pos(2, false), pos(3, true, 48.705, 21.255)))

    @Test fun gpxHasOnlyFixesInOrder() {
        val g = HistoryExport.gpx(t, "LibreTag 0.8.0")
        assertEquals(2, Regex("<trkpt ").findAll(g).count())
        assertTrue(g.contains("<trkpt lat=\"48.7000000\" lon=\"21.2500000\"><time>2027-01-15T09:00:00Z</time></trkpt>"))
        assertTrue(g.indexOf("48.7000000") < g.indexOf("48.7050000"))
        assertTrue(g.contains("Bike &lt;&amp;&gt; &quot;1&quot;"))
        assertFalse(g.contains("<&>"))
    }

    @Test fun csvHasEveryReport() {
        val lines = HistoryExport.csv(t).trimEnd().lines()
        assertEquals(HistoryExport.CSV_HEADER, lines[0])
        assertEquals(4, lines.size)
        val noFix = lines[2].split(",")
        assertEquals("2", noFix[0])
        assertEquals("0", noFix[3])
        assertEquals("", noFix[4])
        assertEquals("48.7100000", noFix[12])
        assertEquals("900", noFix[14])
        assertEquals(HistoryExport.CSV_HEADER.split(",").size, noFix.size)
    }

    @Test fun fileNameIsSafe() {
        assertEquals("libretag-Bike-1-2027-01-15.csv", HistoryExport.fileName(t, "csv", 1_800_000_000_000L))
    }
}
