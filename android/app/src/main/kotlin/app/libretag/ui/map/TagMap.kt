package app.libretag.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.libretag.R
import app.libretag.ui.WRAP
import app.libretag.ui.col
import app.libretag.ui.dp
import app.libretag.ui.flp
import app.libretag.ui.isDark
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * osmdroid with OSM tiles in a muted style (fewer colours, so the blazes stand out), dark tiles
 * in the dark theme, and slots for on-map controls. No location permission and no phone
 * position: the map only shows what the tags reported.
 */
@SuppressLint("ViewConstructor")
class TagMap(ctx: Context) : FrameLayout(ctx) {
    val map = object : MapView(ctx) {
        // keep pans inside the map when it sits in a pager or a scrolling sheet
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(ev)
        }
    }
    /** bottom-right: Fit / Hide map; top-left: Repeaters chip */
    val bottomEnd = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    val topStart = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private var pendingFit: BoundingBox? = null
    private var laidOut = false
    var bottomInset = 0
        set(v) { field = v; (bottomEnd.layoutParams as LayoutParams).bottomMargin = dp(12) + v; bottomEnd.requestLayout() }

    init {
        setBackgroundColor(ctx.col(R.color.map_paper))
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        map.isVerticalMapRepetitionEnabled = false
        map.isHorizontalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.controller.setZoom(7.0)
        map.controller.setCenter(GeoPoint(48.7, 19.5))
        map.overlayManager.tilesOverlay.loadingBackgroundColor = ctx.col(R.color.map_paper)
        map.overlayManager.tilesOverlay.loadingLineColor = ctx.col(R.color.map_paper)
        map.overlayManager.tilesOverlay.setColorFilter(muted(ctx.isDark()))
        addView(map, flp())
        addView(bottomEnd, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        addView(topStart, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            laidOut = true
            flushFit()
        }
    }

    fun setOverlays(vararg o: Overlay) {
        map.overlays.clear()
        map.overlays.addAll(o)
        map.invalidate()
    }

    /**
     * Zoom to show these points (with radii in metres), with room for labels and controls.
     * [animate]: glide there (on a map that shows nothing yet, a short zoom-in at the target).
     */
    fun fit(points: List<Triple<Double, Double, Double>>, maxZoom: Double = 16.0, animate: Boolean = false) {
        val box = boxOf(points) ?: return
        this.maxFitZoom = maxZoom
        this.fitAnimate = animate
        if (laidOut && map.width > dp(50) && map.height > dp(50)) doFit(box) else pendingFit = box
    }

    /** The box around these circles, at least a small neighbourhood wide. */
    private fun boxOf(points: List<Triple<Double, Double, Double>>): BoundingBox? {
        if (points.isEmpty()) return null
        var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
        for ((lat, lon, r) in points) {
            val dLat = r / 110_540.0
            val dLon = r / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
            n = maxOf(n, lat + dLat); s = minOf(s, lat - dLat); e = maxOf(e, lon + dLon); w = minOf(w, lon - dLon)
        }
        val minSpan = 0.0015
        if (n - s < minSpan) { val c = (n + s) / 2; n = c + minSpan / 2; s = c - minSpan / 2 }
        if (e - w < minSpan) { val c = (e + w) / 2; e = c + minSpan / 2; w = c - minSpan / 2 }
        return BoundingBox(n, e, s, w)
    }

    /** All of these circles are on screen now (inside the border kept for labels). */
    fun shows(points: List<Triple<Double, Double, Double>>): Boolean {
        val box = boxOf(points) ?: return true
        if (!laidOut || map.width < dp(50)) return false
        val pj = map.projection
        val inset = dp(28)
        val a = pj.toPixels(GeoPoint(box.latNorth, box.lonWest), null)
        val b = pj.toPixels(GeoPoint(box.latSouth, box.lonEast), null)
        return a.x >= inset && a.y >= inset && b.x <= map.width - inset && b.y <= map.height - inset
    }

    private var maxFitZoom = 16.0
    private var fitAnimate = false
    /** the map has been pointed somewhere (not the default view of the country) */
    private var placed = false

    private fun flushFit() {
        val b = pendingFit ?: return
        if (map.width > dp(50) && map.height > dp(50)) { pendingFit = null; doFit(b) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (laidOut) post { flushFit() }
    }

    /**
     * Zoom so the box fits with a border. Computed here: osmdroid's zoomToBoundingBox can spin
     * forever in Projection.getCloserPixel on a wide map.
     */
    private fun doFit(box: BoundingBox) {
        val z = zoomFor(box, map.width.toDouble(), map.height.toDouble(), dp(56) * 2.0, dp(80).toDouble(), maxFitZoom)
            .coerceIn(map.minZoomLevel, map.maxZoomLevel)
        if (z.isNaN()) return
        val c = GeoPoint((box.latNorth + box.latSouth) / 2, (box.lonEast + box.lonWest) / 2)
        if (fitAnimate) {
            if (!placed) {
                // nothing on the map yet: start just above the target, then zoom in on it
                map.controller.setZoom((z - 2.0).coerceAtLeast(map.minZoomLevel))
                map.controller.setCenter(c)
            }
            map.controller.animateTo(c, z, ANIM_MS)
        } else {
            map.controller.setZoom(z)
            map.controller.setCenter(c)
        }
        placed = true
        map.invalidate()
    }

    val mapReady: Boolean get() = laidOut

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()
    fun onDetach() = map.onDetach()

    companion object {
        const val ANIM_MS = 650L

        /** The zoom at which [box] fits a [w]×[h] px map with [border] px kept free (osmdroid tiles, 256 px). */
        fun zoomFor(box: BoundingBox, w: Double, h: Double, border: Double, minPx: Double, maxZoom: Double): Double {
            val tile = org.osmdroid.util.TileSystem.getTileSize().toDouble()
            val ww = (w - border).coerceAtLeast(minPx)
            val hh = (h - border).coerceAtLeast(minPx)
            fun merc(lat: Double) = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))
            val lonSpan = (box.lonEast - box.lonWest).coerceAtLeast(1e-6)
            val mercSpan = (merc(box.latNorth) - merc(box.latSouth)).coerceAtLeast(1e-8)
            val zx = Math.log(ww * 360.0 / (lonSpan * tile)) / Math.log(2.0)
            val zy = Math.log(hh * 2 * Math.PI / (mercSpan * tile)) / Math.log(2.0)
            return minOf(zx, zy, maxZoom)
        }

        /** Desaturated and lightened OSM tiles; inverted and dimmed in the dark theme. */
        fun muted(dark: Boolean): ColorMatrixColorFilter {
            val sat = ColorMatrix().apply { setSaturation(0.35f) }
            val m = ColorMatrix()
            if (dark) {
                m.set(floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 235f,
                    0f, -0.85f, 0f, 0f, 238f,
                    0f, 0f, -0.85f, 0f, 245f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                m.preConcat(sat)
                val dim = ColorMatrix(floatArrayOf(
                    0.62f, 0f, 0f, 0f, 6f,
                    0f, 0.62f, 0f, 0f, 8f,
                    0f, 0f, 0.62f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                m.postConcat(dim)
            } else {
                m.set(sat)
                val light = ColorMatrix(floatArrayOf(
                    0.9f, 0f, 0f, 0f, 22f,
                    0f, 0.9f, 0f, 0f, 21f,
                    0f, 0f, 0.88f, 0f, 18f,
                    0f, 0f, 0f, 1f, 0f,
                ))
                m.postConcat(light)
            }
            return ColorMatrixColorFilter(m)
        }
    }
}
