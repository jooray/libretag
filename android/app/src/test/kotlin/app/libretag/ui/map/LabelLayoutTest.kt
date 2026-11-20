package app.libretag.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelLayoutTest {
    private val u = 2.75f // dp on the owner's phone
    private val h = 24 * u

    private fun noOverlaps(boxes: List<Box>) {
        for (i in boxes.indices) for (j in i + 1 until boxes.size)
            assertFalse("labels $i and $j overlap: ${boxes[i]} ${boxes[j]}", boxes[i].intersects(boxes[j]))
    }

    @Test fun lonelyLabelsKeepTheirNaturalSpot() {
        val point = TagLabelReq(100f, 500f, 200f, h, area = false)
        val area = TagLabelReq(800f, 500f, 200f, h, area = true)
        val (p, a) = LabelLayout.placeTags(listOf(point, area), emptyList(), u)
        assertEquals(LabelLayout.candidates(point, u)[0], p)
        assertEquals(500f - 6 * u - LabelLayout.STEM * u, p.b, 0.01f)
        assertEquals(500f, a.cy, 0.01f)
        assertEquals(800f, a.cx, 0.01f)
    }

    @Test fun pointNearAnAreaCentreDoesNotCoverIt() {
        // the bug: "heltec ±27 m" (a point) a few px under "Tracker ±4.5 km" (an area centre)
        val heltec = TagLabelReq(540f, 1010f, 420f, h, area = false)
        val tracker = TagLabelReq(536f, 1000f, 380f, h, area = true)
        val boxes = LabelLayout.placeTags(listOf(heltec, tracker), emptyList(), u)
        noOverlaps(boxes)
        // the point keeps its spot; the area label is moved, not dropped
        assertEquals(LabelLayout.candidates(heltec, u)[0], boxes[0])
        assertTrue(boxes[1] != LabelLayout.candidates(tracker, u)[0])
    }

    @Test fun manyTagsOnOnePixelAllGetAVisibleLabel() {
        val reqs = (0 until 6).map { TagLabelReq(500f, 1000f, 300f, h, area = it % 2 == 0) }
        val dots = reqs.filter { !it.area }.map { Box(it.x - 7 * u, it.y - 7 * u, it.x + 7 * u, it.y + 7 * u) }
        val boxes = LabelLayout.placeTags(reqs, dots, u)
        assertEquals(6, boxes.size)
        noOverlaps(boxes)
        boxes.forEach { b -> dots.forEach { assertFalse("label on a dot", it.intersects(b)) } }
    }

    @Test fun overcrowdedStillPlacesEveryLabel() {
        // more labels than stacking steps: none dropped, the least covered spot is taken
        val reqs = (0 until 40).map { TagLabelReq(500f, 1000f, 300f, h, area = true) }
        val boxes = LabelLayout.placeTags(reqs, emptyList(), u)
        assertEquals(40, boxes.size)
    }

    @Test fun farApartLabelsDoNotMove() {
        val reqs = listOf(TagLabelReq(100f, 100f, 200f, h, false), TagLabelReq(900f, 1500f, 200f, h, false))
        val boxes = LabelLayout.placeTags(reqs, emptyList(), u)
        reqs.forEachIndexed { i, q -> assertEquals(LabelLayout.candidates(q, u)[0], boxes[i]) }
    }

    @Test fun twoZonesWithTheSameCircleGetSeparateNames() {
        // "Domov" and "Zóna 2" around the same house
        val a = ZoneLabelReq(500f, 1000f, 80f, 120f, 16 * u)
        val b = ZoneLabelReq(504f, 1002f, 82f, 130f, 16 * u)
        val (za, zb) = LabelLayout.placeZones(listOf(a, b), emptyList(), u)
        assertNotNull(za); assertNotNull(zb)
        assertFalse(za!!.intersects(zb!!))
        // the first one sits under its circle
        assertEquals(1000f + 80f + 5 * u, za.t, 0.01f)
    }

    @Test fun zoneNamesNeverCoverTagLabels() {
        val z = ZoneLabelReq(500f, 1000f, 80f, 120f, 16 * u)
        val tag = Box(300f, 1060f, 700f, 1150f)
        val zb = LabelLayout.placeZones(listOf(z), listOf(tag), u)[0]
        assertNotNull(zb)
        assertFalse(zb!!.intersects(tag))
        // with no free room the zone name is left out, never drawn over a tag
        val wall = Box(0f, 0f, 1080f, 2400f)
        assertNull(LabelLayout.placeZones(listOf(z), listOf(wall), u)[0])
    }
}
