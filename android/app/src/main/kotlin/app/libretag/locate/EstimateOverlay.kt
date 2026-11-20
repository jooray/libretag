package app.libretag.locate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos

/** The 95 % region of an [Estimate] as density-shaded cells (drawn directly, thousands of them). */
class EstimateOverlay(private val e: Estimate) : Overlay() {
    private val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val a = Point()
    private val b = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        val dLat = e.cellM / 110_540.0 / 2
        val dLon = e.cellM / (111_320.0 * cos(Math.toRadians(e.lat))) / 2
        for ((lat, lon, v) in e.cells) {
            pj.toPixels(GeoPoint(lat + dLat, lon - dLon), a)
            pj.toPixels(GeoPoint(lat - dLat, lon + dLon), b)
            paint.color = Color.argb((25 + 110 * v).toInt(), 123, 44, 191)
            c.drawRect(a.x.toFloat(), a.y.toFloat(), b.x.toFloat() + 1, b.y.toFloat() + 1, paint)
        }
    }
}
