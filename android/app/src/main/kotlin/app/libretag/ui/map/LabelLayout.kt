package app.libretag.ui.map

import kotlin.math.max
import kotlin.math.min

/** A screen rectangle in pixels (pure, so the layout can be tested on the JVM). */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    fun overlap(o: Box): Float = max(0f, min(r, o.r) - max(l, o.l)) * max(0f, min(b, o.b) - max(t, o.t))
    fun dy(d: Float) = Box(l, t + d, r, b + d)
    fun grow(g: Float) = Box(l - g, t - g, r + g, b + g)
}

/**
 * A tag's label: the anchor (the dot of a GPS point, the centre of an area) and the pill size.
 * A point's pill sits above its dot on a stem; an area's pill sits on its centre.
 */
data class TagLabelReq(val x: Float, val y: Float, val w: Float, val h: Float, val area: Boolean)

/** A zone's label: the circle (centre, radius in px) and the label size. */
data class ZoneLabelReq(val x: Float, val y: Float, val radius: Float, val w: Float, val h: Float)

/**
 * Where the map labels go so they never sit on each other. Tags first, in the order given (the
 * caller puts the ones that matter most first): each takes the first free spot from its natural
 * one outwards, stacked above and below in pill-height steps (the drawer adds a stem or a leader
 * back to the anchor). A tag's label is never dropped: with no free spot it takes the one that
 * covers the least. Zone labels come after and only take free room; a zone label that would cover
 * a tag label or another zone label is left out.
 *
 * [u] is one dp in pixels.
 */
object LabelLayout {
    /** stem of a point label at its natural spot (dp), as MapInk.label draws it */
    const val STEM = 8f
    private const val STEPS = 8

    /** The natural spot first, then outwards: one step up, one step down, two up, ... */
    fun candidates(q: TagLabelReq, u: Float): List<Box> {
        val step = q.h + 4 * u
        val out = ArrayList<Box>(2 * STEPS + 2)
        if (q.area) {
            val c = Box(q.x - q.w / 2, q.y - q.h / 2, q.x + q.w / 2, q.y + q.h / 2)
            out += c
            for (k in 1..STEPS) { out += c.dy(-k * step); out += c.dy(k * step) }
        } else {
            val bottom = q.y - 6 * u - STEM * u
            val above = Box(q.x - q.w / 2, bottom - q.h, q.x + q.w / 2, bottom)
            // under the dot: clear of the dot's 7 dp halo
            val below = Box(q.x - q.w / 2, q.y + 12 * u, q.x + q.w / 2, q.y + 12 * u + q.h)
            out += above
            for (k in 0 until STEPS) { if (k > 0) out += above.dy(-k * step); out += below.dy(k * step) }
        }
        return out
    }

    /**
     * Places tag labels. [obstacles]: things a label should keep off when it can (the tags' dots).
     * Returns one box per request, in the same order.
     */
    fun placeTags(reqs: List<TagLabelReq>, obstacles: List<Box>, u: Float): List<Box> {
        val placed = ArrayList<Box>(reqs.size)
        val gap = 2 * u
        for (q in reqs) {
            val cands = candidates(q, u)
            val free = cands.firstOrNull { c -> val g = c.grow(gap); placed.none { it.intersects(g) } && obstacles.none { it.intersects(c) } }
            placed += free ?: cands.minByOrNull { c -> placed.sumOf { it.overlap(c).toDouble() } + 0.25 * obstacles.sumOf { it.overlap(c).toDouble() } }!!
        }
        return placed
    }

    /** Zone label spots: under the circle, above it, then further out; null where none is free. */
    fun placeZones(reqs: List<ZoneLabelReq>, taken: List<Box>, u: Float): List<Box?> {
        val used = ArrayList(taken)
        return reqs.map { z ->
            val step = z.h + 3 * u
            val under = Box(z.x - z.w / 2, z.y + z.radius + 5 * u, z.x + z.w / 2, z.y + z.radius + 5 * u + z.h)
            val over = Box(z.x - z.w / 2, z.y - z.radius - 5 * u - z.h, z.x + z.w / 2, z.y - z.radius - 5 * u)
            val cands = listOf(under, over) + (1..3).flatMap { k -> listOf(under.dy(k * step), over.dy(-k * step)) }
            cands.firstOrNull { c -> used.none { it.intersects(c.grow(u)) } }?.also { used += it }
        }
    }
}
