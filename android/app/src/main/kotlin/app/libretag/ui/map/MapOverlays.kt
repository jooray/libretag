package app.libretag.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.core.content.res.ResourcesCompat
import app.libretag.R
import app.libretag.ui.BlazeColors
import app.libretag.ui.BlazeDrawable
import app.libretag.ui.col
import app.libretag.ui.dpf
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/** Shared paints and helpers for the Trail Blaze map marks. */
class MapInk(val ctx: Context) {
    val d = ctx.resources.displayMetrics.density
    val narrowBold: Typeface = ResourcesCompat.getFont(ctx, R.font.archivo_narrow_bold) ?: Typeface.DEFAULT_BOLD
    val narrowSemi: Typeface = ResourcesCompat.getFont(ctx, R.font.archivo_narrow_semibold) ?: Typeface.DEFAULT
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = narrowBold; textSize = 14 * ctx.resources.displayMetrics.scaledDensity.coerceAtMost(d * 1.3f) }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = narrowSemi; textSize = 12 * d }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(46, 0, 0, 0) }
    private val hatches = HashMap<String, BitmapShader>()

    fun c(id: Int) = ctx.col(id)

    /** Diagonal hatch in the blaze colour: the drawn sign of "estimate, not a point". */
    fun hatch(color: String): BitmapShader = hatches.getOrPut(color) {
        val size = (14 * d).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val col = BlazeColors.of(ctx, color)
        cv.drawColor(Color.argb(23, Color.red(col), Color.green(col), Color.blue(col)))
        val p = Paint().apply { this.color = Color.argb(97, Color.red(col), Color.green(col), Color.blue(col)) }
        cv.drawRect(0f, 0f, 4 * d, size.toFloat(), p)
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setRotate(45f) })
        }
    }

    fun px(map: MapView, lat: Double, lon: Double): Point = map.projection.toPixels(GeoPoint(lat, lon), null)

    /** Radius in screen pixels of [meters] around a point. */
    fun radiusPx(pj: Projection, lat: Double, lon: Double, meters: Double): Float {
        val a = pj.toPixels(GeoPoint(lat, lon), null)
        val b = pj.toPixels(GeoPoint(lat + meters / 110_540.0, lon), null)
        return hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())
    }

    /**
     * A label chip: [blaze] + bold name + grey age, with a stem and a dot under it (a point) or
     * centred (an area). Returns the label rectangle for hit testing.
     */
    fun label(
        c: Canvas, x: Float, y: Float, name: String, age: String?, blaze: BlazeDrawable?, alpha: Float = 1f,
        area: Boolean = false, stem: Float = 8f, ring: Int? = null, grey: Boolean = false, iconAlert: Boolean = false,
    ): RectF {
        val h = labelH
        val w = labelW(name, age, blaze != null)
        val top = if (area) y - h / 2 else y - 6 * d - stem * d - h
        val r = RectF(x - w / 2, top, x + w / 2, top + h)
        if (!area) {
            stem(c, x, y, r, alpha, grey)
            dot(c, x, y, alpha, grey)
        }
        chip(c, r, name, age, blaze, alpha, ring, grey)
        return r
    }

    /** Height of a label pill. */
    val labelH get() = 24 * d

    /** Width of a label pill. */
    fun labelW(name: String, age: String?, blaze: Boolean): Float =
        4 * d + (if (blaze) 17 * d + 6 * d else 4 * d) + text.measureText(name) + (age?.let { small.measureText(it) + 6 * d } ?: 0f) + 8 * d

    /** The dot of a GPS point. */
    fun dot(c: Canvas, x: Float, y: Float, alpha: Float = 1f, grey: Boolean = false) {
        val a = (alpha * 255).toInt()
        fill.shader = null
        fill.color = c(R.color.chip); fill.alpha = a
        c.drawCircle(x, y, 7 * d, fill)
        fill.color = c(if (grey) R.color.ink3 else R.color.ink); fill.alpha = a
        c.drawCircle(x, y, 5 * d, fill)
    }

    /** A stem or leader from an anchor at ([x], [y]) to the nearest edge of its pill [r]. */
    fun stem(c: Canvas, x: Float, y: Float, r: RectF, alpha: Float = 1f, grey: Boolean = false, width: Float = 2f) {
        stroke.pathEffect = null; stroke.strokeWidth = width * d; stroke.color = c(if (grey) R.color.ink3 else R.color.ink)
        stroke.alpha = (alpha * 255).toInt()
        val ex = x.coerceIn(r.left + 4 * d, r.right - 4 * d)
        if (r.top >= y) c.drawLine(x, y + 6 * d, ex, r.top, stroke)
        else if (r.bottom <= y) c.drawLine(x, y - 6 * d, ex, r.bottom, stroke)
    }

    /** The pill itself at [r]. */
    fun chip(c: Canvas, r: RectF, name: String, age: String?, blaze: BlazeDrawable?, alpha: Float = 1f, ring: Int? = null, grey: Boolean = false) {
        val padL = 4 * d
        val bw = if (blaze != null) 17 * d else 0f
        val nameW = text.measureText(name)
        val a = (alpha * 255).toInt()
        shadow.alpha = (46 * alpha).toInt()
        c.drawRoundRect(RectF(r.left, r.top + 1 * d, r.right, r.bottom + 2 * d), 4 * d, 4 * d, shadow)
        fill.color = c(if (grey) R.color.surf3 else R.color.chip); fill.alpha = a
        c.drawRoundRect(r, 4 * d, 4 * d, fill)
        if (ring != null) {
            stroke.color = ring; stroke.alpha = a; stroke.strokeWidth = 2 * d
            c.drawRoundRect(r, 4 * d, 4 * d, stroke)
        } else if (grey) {
            stroke.color = c(R.color.outline); stroke.alpha = a; stroke.strokeWidth = 1 * d
            c.drawRoundRect(r, 4 * d, 4 * d, stroke)
        }
        var cx = r.left + padL
        if (blaze != null) {
            blaze.setBounds(cx.toInt(), (r.top + 3 * d).toInt(), (cx + bw).toInt(), (r.bottom - 3 * d).toInt())
            blaze.alpha = a
            blaze.draw(c)
            cx += bw + 6 * d
        } else cx += 4 * d
        text.color = c(if (grey) R.color.ink2 else R.color.ink); text.alpha = a
        val base = r.centerY() - (text.descent() + text.ascent()) / 2
        c.drawText(name, cx, base, text)
        if (age != null) {
            small.color = c(R.color.ink3); small.alpha = a
            c.drawText(age, cx + nameW + 6 * d, base, small)
        }
    }
}

/** A tag on the all-tags map. */
data class MapTag(
    val id: String,
    val name: String,
    val color: String,
    val symbol: String,
    val lat: Double,
    val lon: Double,
    /** null: a GPS point; else the 68 % radius of an area */
    val areaM: Double?,
    val age: String?,
    val alpha: Float,
    /** crit (red ring) or warn (orange ring) */
    val ring: Int?,
    val priority: Int,
)

/** A zone circle (home etc.) drawn dashed in ink. */
data class MapZone(val name: String, val lat: Double, val lon: Double, val radiusM: Double)

/** All tags: blazes with names, estimates as hatched dashed circles, older ones faded. */
class AllTagsOverlay(ctx: Context, private val onTap: (String) -> Unit) : Overlay() {
    private val ink = MapInk(ctx)
    var tags: List<MapTag> = emptyList()
    var zones: List<MapZone> = emptyList()
    private val hits = mutableListOf<Pair<RectF, String>>()
    private val blazes = HashMap<String, BlazeDrawable>()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        val d = ink.d
        hits.clear()
        for (z in zones) drawZone(c, map, ink, z, false, label = null)
        val px = tags.associate { it.id to pj.toPixels(GeoPoint(it.lat, it.lon), null) }
        // estimate areas, the alarms on top
        for (t in tags.filter { it.areaM != null }.sortedBy { -it.priority }) {
            val p = px.getValue(t.id)
            val r = ink.radiusPx(pj, t.lat, t.lon, t.areaM!!)
            ink.fill.shader = ink.hatch(t.color); ink.fill.alpha = (255 * t.alpha).toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.fill)
            ink.fill.shader = null
            ink.stroke.color = BlazeColors.of(ink.ctx, t.color); ink.stroke.alpha = (255 * t.alpha).toInt()
            ink.stroke.strokeWidth = 2 * d; ink.stroke.pathEffect = DashPathEffect(floatArrayOf(7 * d, 5 * d), 0f)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.stroke)
            ink.stroke.pathEffect = null
        }
        // labels laid out together (LabelLayout), recomputed on every draw so zoom and pan follow;
        // the natural spot goes to alarms first, then points (they are exact), then areas
        val placing = tags.sortedWith(compareBy<MapTag>({ -it.priority }, { if (it.areaM != null) 1 else 0 }))
        val reqs = placing.map { t ->
            val p = px.getValue(t.id)
            TagLabelReq(p.x.toFloat(), p.y.toFloat(), ink.labelW(t.name, t.age, true), ink.labelH, t.areaM != null)
        }
        val dots = placing.filter { it.areaM == null }.map { t ->
            val p = px.getValue(t.id)
            Box(p.x - 7 * d, p.y - 7 * d, p.x + 7 * d, p.y + 7 * d)
        }
        val boxes = LabelLayout.placeTags(reqs, dots, d)
        // zone names only where they cover no tag label, no dot and no other zone name
        val zreq = zones.map { z ->
            val p = pj.toPixels(GeoPoint(z.lat, z.lon), null)
            ZoneLabelReq(p.x.toFloat(), p.y.toFloat(), ink.radiusPx(pj, z.lat, z.lon, z.radiusM), zoneLabelW(ink, z.name), 16 * d)
        }
        LabelLayout.placeZones(zreq, boxes + dots, d).forEachIndexed { i, b -> if (b != null) drawZoneLabel(c, ink, zones[i].name, b) }
        // stems and leaders, then dots, then the pills (the first placed on top)
        placing.forEachIndexed { i, t ->
            val p = px.getValue(t.id); val r = boxes[i].rect()
            val x = p.x.toFloat(); val y = p.y.toFloat()
            if (t.areaM == null) ink.stem(c, x, y, r, t.alpha)
            else if (!r.contains(x, y)) ink.stem(c, x, y, r, t.alpha, width = 1.5f)
        }
        placing.forEachIndexed { i, t ->
            val p = px.getValue(t.id); val x = p.x.toFloat(); val y = p.y.toFloat()
            if (t.areaM == null) ink.dot(c, x, y, t.alpha)
            else if (!boxes[i].rect().contains(x, y)) {
                // a moved area label: a small centre mark in the tag's colour at the leader's end
                ink.fill.shader = null; ink.fill.color = BlazeColors.of(ink.ctx, t.color); ink.fill.alpha = (255 * t.alpha).toInt()
                c.drawCircle(x, y, 4 * d, ink.fill)
            }
        }
        for (i in placing.indices.reversed()) {
            val t = placing[i]; val r = boxes[i].rect()
            val blaze = blazes.getOrPut(t.id) { BlazeDrawable(ink.ctx, t.color, t.symbol) }.also { it.color = t.color; it.symbol = t.symbol }
            ink.chip(c, r, t.name, t.age, blaze, t.alpha, t.ring)
            hits += r to t.id
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = hits.lastOrNull { it.first.contains(e.x, e.y) } ?: return false
        onTap(hit.second)
        return true
    }
}

fun drawZone(c: Canvas, map: MapView, ink: MapInk, z: MapZone, edit: Boolean, label: String? = z.name) {
    val d = ink.d
    val p = map.projection.toPixels(GeoPoint(z.lat, z.lon), null)
    val r = ink.radiusPx(map.projection, z.lat, z.lon, z.radiusM)
    ink.fill.shader = null
    ink.fill.color = if (edit) ink.c(R.color.focus) else ink.c(R.color.ink)
    ink.fill.alpha = if (edit) 31 else 13
    c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.fill)
    ink.stroke.color = if (edit) ink.c(R.color.focus) else ink.c(R.color.ink3)
    ink.stroke.alpha = 255
    ink.stroke.strokeWidth = (if (edit) 2f else 1.5f) * d
    ink.stroke.pathEffect = if (edit) null else DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.stroke)
    ink.stroke.pathEffect = null
    if (label != null) {
        val w = zoneLabelW(ink, label)
        val y = if (edit) p.y.toFloat() else p.y + r + 14 * d
        drawZoneLabel(c, ink, label, Box(p.x - w / 2, y - 9 * d, p.x + w / 2, y + 7 * d))
    }
}

fun zoneLabelW(ink: MapInk, label: String) = ink.small.measureText(label) + 8 * ink.d

/** A zone's name on a paper plate at [b] (16 dp high). */
fun drawZoneLabel(c: Canvas, ink: MapInk, label: String, b: Box) {
    val d = ink.d
    ink.small.color = ink.c(R.color.ink3); ink.small.alpha = 255
    ink.fill.shader = null; ink.fill.color = ink.c(R.color.map_paper); ink.fill.alpha = 204
    c.drawRoundRect(b.rect(), 3 * d, 3 * d, ink.fill)
    c.drawText(label, b.l + 4 * d, b.t + 12 * d, ink.small)
}

fun Box.rect() = RectF(l, t, r, b)

/** One report on the detail map ([at]: when it was received, ms). */
data class TrackPoint(val n: Long, val lat: Double, val lon: Double, val areaM68: Double? = null, val areaM95: Double? = null, val at: Long = 0)

/** A repeater near the tag (Repeaters toggle). */
data class MapRepeater(val name: String, val lat: Double, val lon: Double, val snrDb: Double)

/**
 * One tag's trail: a dashed line in the blaze colour with stations labelled by clock time where
 * the labels fit (the newest and the oldest first, then the others newest first, skipping any
 * that would overlap; the rest are plain dots and a tap shows their time),
 * the selected report as the tag's label, an estimate as a hatched 68 % area with a dashed 95 %
 * outline, the last GPS fix as a grey mark, zones, and repeaters on request.
 */
class TrailOverlay(ctx: Context) : Overlay() {
    private val ink = MapInk(ctx)
    var color = "yellow"
    var symbol = "stripe"
    var name = ""
    var points: List<TrackPoint> = emptyList()
    var selected: TrackPoint? = null
    var selectedLabel: String = ""
    var selectedAge: String? = null
    var selectedAlpha = 1f
    var selectedRing: Int? = null
    /** selected report has no fix: the last GPS (grey) */
    var lastGps: Pair<GeoPoint, String>? = null
    var zones: List<MapZone> = emptyList()
    var repeaters: List<MapRepeater> = emptyList()
    var bubble: Int = -1
    /** a station's chip text ("14:32") */
    var timeOf: (Long) -> String = { "" }
    /** a tapped station: clock time, how long ago, report number */
    var bubbleOf: (TrackPoint) -> Triple<String, String, String> = { Triple("", "", "") }
    /** the station whose bubble is open (report number) */
    var pointBubble: Long? = null
    private val stationHits = mutableListOf<Pair<RectF, TrackPoint>>()
    private val blaze = BlazeDrawable(ctx, color, symbol)
    private val repHits = mutableListOf<Pair<RectF, Int>>()
    private val path = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        val d = ink.d
        val col = BlazeColors.of(ink.ctx, color)
        blaze.color = color; blaze.symbol = symbol
        for (z in zones) drawZone(c, map, ink, z, false)
        val pts = points.map { it to pj.toPixels(GeoPoint(it.lat, it.lon), null) }
        if (pts.size > 1) {
            path.reset()
            pts.forEachIndexed { i, (_, p) -> if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat()) }
            ink.stroke.pathEffect = null; ink.stroke.color = ink.c(R.color.map_paper); ink.stroke.alpha = 255; ink.stroke.strokeWidth = 7 * d
            c.drawPath(path, ink.stroke)
            ink.stroke.color = col; ink.stroke.strokeWidth = 3 * d
            ink.stroke.pathEffect = DashPathEffect(floatArrayOf(9 * d, 6 * d), 0f)
            c.drawPath(path, ink.stroke)
            ink.stroke.pathEffect = null
        }
        val sel = selected
        // estimate of the selected report
        if (sel?.areaM68 != null) {
            val p = pj.toPixels(GeoPoint(sel.lat, sel.lon), null)
            sel.areaM95?.let { r95 ->
                ink.stroke.color = col; ink.stroke.strokeWidth = 1.5f * d
                ink.stroke.pathEffect = DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), ink.radiusPx(pj, sel.lat, sel.lon, r95), ink.stroke)
            }
            val r = ink.radiusPx(pj, sel.lat, sel.lon, sel.areaM68)
            ink.fill.shader = ink.hatch(color); ink.fill.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.fill)
            ink.fill.shader = null
            ink.stroke.strokeWidth = 2.5f * d; ink.stroke.pathEffect = DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, ink.stroke)
            ink.stroke.pathEffect = null
        }
        // stations: dots everywhere, time chips only where they don't collide
        val selPx = sel?.let { pj.toPixels(GeoPoint(it.lat, it.lon), null) }
        val placed = mutableListOf<RectF>()
        if (selPx != null) {
            // keep clear of the tag's own label (name + time above the point, or centred on an area)
            val w = ink.text.measureText(selectedLabel) + (selectedAge?.let { ink.small.measureText(it) } ?: 0f) + 48 * d
            placed += if (sel.areaM68 != null) RectF(selPx.x - w / 2, selPx.y - 16 * d, selPx.x + w / 2, selPx.y + 16 * d)
                else RectF(selPx.x - w / 2, selPx.y - 44 * d, selPx.x + w / 2, selPx.y + 10 * d)
        }
        lastGps?.let { (gp, _) ->
            val q = pj.toPixels(gp, null)
            placed += RectF(q.x - 70 * d, q.y - 44 * d, q.x + 70 * d, q.y + 10 * d)
        }
        stationHits.clear()
        val others = pts.filter { (tp, _) -> sel == null || tp.n != sel.n }
        for ((tp, p) in others) {
            ink.fill.color = ink.c(R.color.chip); ink.fill.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4.5f * d, ink.fill)
            ink.stroke.color = col; ink.stroke.strokeWidth = 2 * d
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 4.5f * d, ink.stroke)
            stationHits += RectF(p.x - 16 * d, p.y - 16 * d, p.x + 16 * d, p.y + 16 * d) to tp
        }
        // newest, oldest, then the rest newest first
        val order = if (others.size <= 2) others.reversed() else listOf(others.last(), others.first()) + others.subList(1, others.size - 1).reversed()
        ink.small.typeface = ink.narrowBold; ink.small.textSize = 11 * d
        for ((tp, p) in order) {
            if (tp.at <= 0) continue
            val label = timeOf(tp.at)
            val w = ink.small.measureText(label) + 12 * d
            // the chip sits just above its dot
            val r = RectF(p.x - w / 2, p.y - 27 * d, p.x + w / 2, p.y - 9 * d)
            val pad = RectF(r).apply { inset(-3 * d, -3 * d) }
            if (placed.any { RectF.intersects(it, pad) }) continue
            placed += r
            ink.fill.color = ink.c(R.color.chip); ink.fill.alpha = 235
            c.drawRoundRect(r, 9 * d, 9 * d, ink.fill)
            ink.stroke.color = col; ink.stroke.strokeWidth = 1.5f * d
            c.drawRoundRect(r, 9 * d, 9 * d, ink.stroke)
            ink.small.color = ink.c(R.color.ink); ink.small.alpha = 255
            c.drawText(label, r.centerX() - ink.small.measureText(label) / 2, r.centerY() - (ink.small.descent() + ink.small.ascent()) / 2, ink.small)
        }
        ink.small.typeface = ink.narrowSemi; ink.small.textSize = 12 * d
        lastGps?.let { (gp, age) ->
            val p = pj.toPixels(gp, null)
            // keep the grey label clear of the tag's own label
            val sp = sel?.let { pj.toPixels(GeoPoint(it.lat, it.lon), null) }
            var stem = 8f
            if (sp != null) {
                val dx = kotlin.math.abs(sp.x - p.x); val dy = sp.y - p.y
                if (dx < 200 * d && dy > -80 * d && dy < 80 * d) stem = (-dy / d + 14f).coerceIn(8f, 130f)
            }
            ink.label(c, p.x.toFloat(), p.y.toFloat(), ink.ctx.getString(R.string.map_last_gps), age, null, grey = true, stem = stem)
        }
        if (sel != null) {
            val p = pj.toPixels(GeoPoint(sel.lat, sel.lon), null)
            ink.label(c, p.x.toFloat(), p.y.toFloat(), selectedLabel, selectedAge, blaze, selectedAlpha,
                area = sel.areaM68 != null, ring = selectedRing)
        }
        repHits.clear()
        repeaters.forEachIndexed { i, r ->
            val p = pj.toPixels(GeoPoint(r.lat, r.lon), null)
            val rect = RectF(p.x - 14 * d, p.y - 14 * d, p.x + 14 * d, p.y + 14 * d)
            ink.fill.color = ink.c(R.color.ink); ink.fill.alpha = 255
            c.drawRoundRect(rect, 6 * d, 6 * d, ink.fill)
            ink.stroke.color = ink.c(R.color.bg); ink.stroke.strokeWidth = 2 * d
            c.drawCircle(p.x.toFloat(), p.y - 3 * d, 1.6f * d, ink.stroke)
            c.drawLine(p.x.toFloat(), p.y - 1 * d, p.x.toFloat(), p.y + 8 * d, ink.stroke)
            c.drawLine(p.x - 4 * d, p.y + 8 * d, p.x + 4 * d, p.y + 8 * d, ink.stroke)
            repHits += RectF(rect).apply { inset(-10 * d, -10 * d) } to i
            if (i == bubble) {
                val txt = ink.ctx.getString(R.string.repeater_bubble, r.name, String.format(java.util.Locale.ROOT, "%+.1f", r.snrDb))
                ink.text.color = ink.c(R.color.on_inverse)
                val w = ink.text.measureText(txt) + 20 * d
                val b = RectF(p.x - w / 2, p.y - 22 * d - 30 * d, p.x + w / 2, p.y - 22 * d)
                ink.fill.color = ink.c(R.color.inverse)
                c.drawRoundRect(b, 6 * d, 6 * d, ink.fill)
                c.drawText(txt, b.left + 10 * d, b.centerY() - (ink.text.descent() + ink.text.ascent()) / 2, ink.text)
            }
        }
        drawPointBubble(c, map)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = repHits.firstOrNull { it.first.contains(e.x, e.y) }
        if (hit != null) {
            bubble = if (hit.second == bubble) -1 else hit.second
            pointBubble = null
            mapView.invalidate()
            return true
        }
        val st = stationHits.lastOrNull { it.first.contains(e.x, e.y) }
        bubble = -1
        pointBubble = if (st == null || st.second.n == pointBubble) null else st.second.n
        mapView.invalidate()
        return st != null
    }

    /** A tapped station: "14:32 · 5 h ago" and, small, the report number. */
    private fun drawPointBubble(c: Canvas, map: MapView) {
        val n = pointBubble ?: return
        val tp = points.firstOrNull { it.n == n } ?: run { pointBubble = null; return }
        val d = ink.d
        val (at, ago, num) = bubbleOf(tp)
        val p = map.projection.toPixels(GeoPoint(tp.lat, tp.lon), null)
        ink.text.color = ink.c(R.color.on_inverse); ink.text.alpha = 255
        ink.small.color = ink.c(R.color.on_inverse); ink.small.alpha = 255
        val main = at
        val second = "$ago · $num"
        val w = maxOf(ink.text.measureText(main), ink.small.measureText(second)) + 20 * d
        val b = RectF(p.x - w / 2, p.y - 14 * d - 44 * d, p.x + w / 2, p.y - 14 * d)
        ink.fill.color = ink.c(R.color.inverse); ink.fill.alpha = 255
        c.drawRoundRect(b, 6 * d, 6 * d, ink.fill)
        c.drawText(main, b.left + 10 * d, b.top + 19 * d, ink.text)
        ink.small.alpha = 200
        c.drawText(second, b.left + 10 * d, b.top + 36 * d, ink.small)
        ink.small.alpha = 255
    }
}

/**
 * Place editor: circles, the selected one (the place being edited) blue with a drag handle on its
 * edge. A single tap is handed to [onTapMap], which moves the selected circle; the overlay itself
 * never adds one.
 */
class ZoneEditOverlay(ctx: Context, private val onTapMap: (GeoPoint) -> Unit, private val onRadius: (Double) -> Unit) : Overlay() {
    private val ink = MapInk(ctx)
    var zones: List<MapZone> = emptyList()
    var selected = -1
    var tag: Triple<Double, Double, Pair<String, String>>? = null
    var tagName = ""
    var labelFor: (MapZone) -> String = { it.name }
    private var dragging = false
    private val blaze = BlazeDrawable(ctx, "yellow", "stripe")

    private fun handle(map: MapView): Point? {
        val z = zones.getOrNull(selected) ?: return null
        val c = map.projection.toPixels(GeoPoint(z.lat, z.lon), null)
        return Point((c.x + ink.radiusPx(map.projection, z.lat, z.lon, z.radiusM)).toInt(), c.y)
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val d = ink.d
        zones.forEachIndexed { i, z -> drawZone(c, map, ink, z, i == selected, labelFor(z)) }
        tag?.let { (lat, lon, mark) ->
            blaze.color = mark.first; blaze.symbol = mark.second
            val p = map.projection.toPixels(GeoPoint(lat, lon), null)
            ink.label(c, p.x.toFloat(), p.y.toFloat(), tagName, null, blaze)
        }
        handle(map)?.let { h ->
            ink.fill.color = ink.c(R.color.focus); ink.fill.alpha = 255
            ink.shadow.alpha = 60
            c.drawCircle(h.x.toFloat(), h.y + 1.5f * d, 15 * d, ink.shadow)
            c.drawCircle(h.x.toFloat(), h.y.toFloat(), 14 * d, ink.fill)
            ink.stroke.color = Color.WHITE; ink.stroke.strokeWidth = 3 * d; ink.stroke.pathEffect = null
            c.drawCircle(h.x.toFloat(), h.y.toFloat(), 12.5f * d, ink.stroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        val h = handle(map)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (h != null && hypot(e.x - h.x, e.y - h.y) < 28 * ink.d) { dragging = true; return true }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val z = zones.getOrNull(selected) ?: return false
                val cpx = map.projection.toPixels(GeoPoint(z.lat, z.lon), null)
                val px = hypot(e.x - cpx.x, e.y - cpx.y)
                val perPx = z.radiusM / ink.radiusPx(map.projection, z.lat, z.lon, z.radiusM)
                onRadius(px * perPx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; return true }
        }
        return false
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        // a tap on the handle ends a short drag, it is not a move
        handle(map)?.let { h -> if (hypot(e.x - h.x, e.y - h.y) < 28 * ink.d) return true }
        val gp = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        onTapMap(gp)
        return true
    }
}
