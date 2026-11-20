package app.libretag.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val now = 1_800_000_000L
    private fun live(k: String, name: String, at: Long?) = BackupTag(name, k, "thing", "red-stripe", updated = at)
    private fun p(created: Long, vararg t: BackupTag) = BackupPayload(created = created, owner = null, tags = t.toList())

    @Test fun newestUpdateWinsPerTag() {
        val local = p(now, live(a, "Keys", now - 10), live(b, "Bag", now - 100))
        val remote = p(now, live(a, "Old keys", now - 50), live(b, "Backpack", now - 5), live(c, "Bike", now - 1))
        val m = BackupMerge.merge(listOf(local, remote), now)
        assertEquals(listOf("Keys", "Backpack", "Bike"), m.map { it.name })
        assertEquals(listOf(now - 10, now - 5, now - 1), m.map { it.updated })
    }

    @Test fun removalWinsOnlyWhenNewer() {
        val local = p(now, live(a, "Keys", now - 10), live(b, "Bag", now - 100))
        val remote = p(now, BackupTag.tombstone(a, now - 20), BackupTag.tombstone(b, now - 50))
        val m = BackupMerge.merge(listOf(local, remote), now)
        assertEquals("Keys", m[0].name)
        assertTrue(m[1].isRemoved)
        assertEquals(now - 50, m[1].removed)
        // and the tombstone travels on in the merged result
        val again = BackupMerge.merge(listOf(p(now, live(b, "Bag", now - 100)), p(now, *m.toTypedArray())), now)
        assertTrue(again.first { it.k0Hex == b }.isRemoved)
    }

    @Test fun tieKeepsTheLiveTag() {
        val m = BackupMerge.merge(listOf(p(now, BackupTag.tombstone(a, now - 5)), p(now, live(a, "Keys", now - 5))), now)
        assertEquals(listOf("Keys"), m.map { it.name })
    }

    @Test fun tieBetweenLiveTagsKeepsTheFirstSource() {
        val m = BackupMerge.merge(listOf(p(now, live(a, "Mine", now)), p(now, live(a, "Theirs", now))), now)
        assertEquals("Mine", m.single().name)
    }

    @Test fun tombstonesExpireAfter90Days() {
        val old = BackupTag.tombstone(a, now - BackupMerge.TOMBSTONE_KEEP_S)
        val recent = BackupTag.tombstone(b, now - BackupMerge.TOMBSTONE_KEEP_S + 1)
        val m = BackupMerge.merge(listOf(p(now, old, recent)), now)
        assertEquals(listOf(b), m.map { it.k0Hex })
    }

    @Test fun v1TagsWithoutUpdatedUseCreated() {
        val v1 = p(now - 1000, live(a, "From v1", null))
        val newer = p(now, live(a, "Renamed", now - 500))
        assertEquals("Renamed", BackupMerge.merge(listOf(v1, newer), now).single().name)
        val older = p(now, live(a, "Renamed long ago", now - 2000))
        val m = BackupMerge.merge(listOf(older, v1), now).single()
        assertEquals("From v1", m.name)
        assertEquals(now - 1000, m.updated)
    }

    @Test fun k0IsCaseInsensitive() {
        val m = BackupMerge.merge(listOf(p(now, live(a.uppercase(), "Up", now - 1)), p(now, live(a, "Low", now))), now)
        assertEquals(1, m.size)
        assertEquals(a, m.single().k0Hex)
        assertEquals("Low", m.single().name)
    }

    @Test fun plaintextRoundTripV11() {
        val pl = BackupPayload(created = now, owner = null, d = "0f".repeat(16),
            tags = listOf(live(a, "Keys \"x\"", now - 1), BackupTag.tombstone(b, now - 2)))
        val s = KeyBackup.plaintext(pl)
        assertEquals("{\"v\":1,\"created\":1800000000,\"owner\":null,\"d\":\"${"0f".repeat(16)}\",\"tags\":[" +
            "{\"name\":\"Keys \\\"x\\\"\",\"k0\":\"$a\",\"type\":\"thing\",\"mark\":\"red-stripe\",\"updated\":1799999999}," +
            "{\"k0\":\"$b\",\"removed\":1799999998}]}", s)
        assertEquals(pl, KeyBackup.parse(s))
        // readers ignore unknown keys and drop tags with a bad k0
        val extra = KeyBackup.parse("{\"v\":1,\"created\":5,\"owner\":null,\"x\":[1],\"tags\":[{\"name\":\"n\",\"k0\":\"zz\",\"type\":\"thing\",\"mark\":\"\"},{\"k0\":\"$c\",\"name\":\"ok\",\"new\":true}]}")!!
        assertEquals(listOf(c), extra.tags.map { it.k0Hex })
        assertEquals(null, KeyBackup.parse("{\"v\":2,\"created\":5,\"owner\":null,\"tags\":[]}"))
        assertEquals(null, KeyBackup.parse("not json"))
    }

    @Test fun settingsRoundTrip() {
        val st = TagSettings(intervalMin = 60, checkS = 20, still = TagSettings.Still(3, 6), use = "things", fw = "0.7.0",
            zones = listOf(BackupZone("Home", 48.70, 21.25, 150)), anchors = listOf(BackupAnchor("Car stereo", "public")),
            alerts = TagSettings.Alerts(silent = true, lowBattery = false, leftZone = true, guard = true),
            guard = TagSettings.Guard(48.71, 21.26, 300, 1_799_990_000))
        val pl = BackupPayload(created = now, owner = null, tags = listOf(live(a, "Car", now).copy(settings = st),
            live(b, "Bag", now).copy(settings = st.copy(still = null, fw = null, zones = emptyList(), anchors = emptyList(), guard = null))))
        val s = KeyBackup.plaintext(pl)
        assertTrue(s, s.contains("\"updated\":1800000000,\"interval_min\":60,\"report_every\":1,\"check_s\":20,\"still\":{\"after\":3,\"hb_h\":6},\"use\":\"things\",\"fw\":\"0.7.0\"," +
            "\"zones\":[{\"name\":\"Home\",\"lat\":48.7,\"lon\":21.25,\"r_m\":150}],\"anchors\":[{\"label\":\"Car stereo\",\"kind\":\"public\"}]," +
            "\"alerts\":{\"silent\":true,\"low_battery\":false,\"left_zone\":true,\"guard\":true},\"guard\":{\"lat\":48.71,\"lon\":21.26,\"r_m\":300,\"since\":1799990000}}"))
        assertTrue(s, s.contains("\"still\":null,\"use\":\"things\",\"fw\":null,\"zones\":[],\"anchors\":[]"))
        assertEquals(pl, KeyBackup.parse(s))
        // 0.7.3 report_every round-trips; a backup without it means 1
        val p3 = pl.copy(tags = listOf(pl.tags[0].copy(settings = st.copy(reportEvery = 3))))
        assertEquals(3, KeyBackup.parse(KeyBackup.plaintext(p3))!!.tags[0].settings!!.reportEvery)
        assertEquals(1, KeyBackup.parse(s.replace("\"report_every\":1,", ""))!!.tags[0].settings!!.reportEvery)
        // a settings change counts as an edit
        val bk = BackupBook().track(listOf(pl.tags[0]), now)
        assertEquals(now + 5, bk.track(listOf(pl.tags[0].copy(settings = st.copy(checkS = 0))), now + 5).seen[a]!!.second)
    }
}
