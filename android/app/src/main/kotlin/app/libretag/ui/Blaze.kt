package app.libretag.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.libretag.R

/** Blaze colours (Trail Blaze palette), the same in light and dark themes. */
object BlazeColors {
    fun of(ctx: Context, color: String): Int = ContextCompat.getColor(ctx, when (color) {
        "red" -> R.color.b_red
        "blue" -> R.color.b_blue
        "green" -> R.color.b_green
        else -> R.color.b_yellow
    })

    fun colorName(ctx: Context, color: String): String = ctx.getString(when (color) {
        "red" -> R.string.mark_red
        "blue" -> R.string.mark_blue
        "green" -> R.string.mark_green
        else -> R.string.mark_yellow
    })

    fun symbolName(ctx: Context, symbol: String): String = ctx.getString(when (symbol) {
        "stripe" -> R.string.mark_stripe
        "diag" -> R.string.mark_diag
        "triangle" -> R.string.mark_triangle
        "circle" -> R.string.mark_circle
        else -> R.string.mark_square
    })

    fun markName(ctx: Context, color: String, symbol: String) =
        ctx.getString(R.string.mark_name, colorName(ctx, color), symbolName(ctx, symbol))
}

/**
 * A trail blaze: a white plate with a coloured symbol and a graphite edge, 20:24 like a painted
 * hiking-trail marker. Draws itself in any bounds (keeps the aspect, centred).
 */
class BlazeDrawable(private val ctx: Context, var color: String, var symbol: String, private val grey: Boolean = false) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width() / 20f, b.height() / 24f)
        val ox = b.left + (b.width() - 20 * s) / 2
        val oy = b.top + (b.height() - 24 * s) / 2
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        fill.color = Color.WHITE
        c.drawRect(x(1f), y(1f), x(19f), y(23f), fill)
        fill.color = if (grey) Color.rgb(0x9A, 0x9A, 0x9A) else BlazeColors.of(ctx, color)
        path.reset()
        when (symbol) {
            "stripe" -> c.drawRect(x(1f), y(8f), x(19f), y(16f), fill)
            "diag" -> {
                path.moveTo(x(1f), y(16.5f)); path.lineTo(x(13.5f), y(1f)); path.lineTo(x(19f), y(1f))
                path.lineTo(x(19f), y(6.5f)); path.lineTo(x(6.5f), y(23f)); path.lineTo(x(1f), y(23f)); path.close()
                c.drawPath(path, fill)
            }
            "triangle" -> {
                path.moveTo(x(10f), y(5.5f)); path.lineTo(x(16.5f), y(17.5f)); path.lineTo(x(3.5f), y(17.5f)); path.close()
                c.drawPath(path, fill)
            }
            "circle" -> c.drawCircle(x(10f), y(12f), 5.5f * s, fill)
            else -> c.drawRect(x(4.5f), y(6.5f), x(15.5f), y(17.5f), fill)
        }
        edge.color = ContextCompat.getColor(ctx, R.color.blaze_edge)
        edge.strokeWidth = 1.5f * s
        c.drawRoundRect(RectF(x(0.75f), y(0.75f), x(19.25f), y(23.25f)), s, s, edge)
    }

    override fun getIntrinsicWidth() = (20 * ctx.resources.displayMetrics.density * 1.17f).toInt()
    override fun getIntrinsicHeight() = (24 * ctx.resources.displayMetrics.density * 1.17f).toInt()
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; edge.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The signpost plate: a rectangle with a 2 dp border and an arrow tip on the right, like the
 * boards on a trail signpost. [fillColor] is the plate ground, [strokeColor] its edge.
 */
class PlateDrawable(private val ctx: Context, var fillColor: Int, var strokeColor: Int) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val tipW = 20 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; strokeWidth = 2 * d }
    private val path = Path()
    /** height of a status band drawn across the top (0 = none) and its colour */
    var bandHeight = 0f
    var bandColor = 0

    override fun draw(c: Canvas) {
        val b = bounds
        val h = stroke.strokeWidth / 2
        val right = b.right - tipW
        path.reset()
        path.moveTo(b.left + h, b.top + h)
        path.lineTo(right, b.top + h)
        path.lineTo(b.right - h * 1.5f, b.exactCenterY())
        path.lineTo(right, b.bottom - h)
        path.lineTo(b.left + h, b.bottom - h)
        path.close()
        fill.color = fillColor
        c.drawPath(path, fill)
        if (bandHeight > 0) {
            c.save()
            c.clipPath(path)
            fill.color = bandColor
            c.drawRect(b.left.toFloat(), b.top.toFloat(), right + tipW, b.top + bandHeight, fill)
            c.restore()
        }
        stroke.color = strokeColor
        c.drawPath(path, stroke)
    }

    val tipWidth: Int get() = tipW.toInt()
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Battery outline with a fill bar; the bar turns warn-orange below 20 %. */
class BatteryDrawable(private val ctx: Context, var level: Int?, var color: Int) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width() / 22f, b.height() / 12f)
        val ox = b.left.toFloat()
        val oy = b.top + (b.height() - 12 * s) / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * s; p.color = color
        c.drawRoundRect(RectF(ox + 0.75f * s, oy + 0.75f * s, ox + 18.75f * s, oy + 11.25f * s), 2 * s, 2 * s, p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(ox + 20 * s, oy + 4 * s, ox + 21.6f * s, oy + 8 * s), 0.6f * s, 0.6f * s, p)
        val lv = level ?: return
        val w = maxOf(1.5f, 14f * lv / 100f)
        p.color = if (lv < 20) ContextCompat.getColor(ctx, R.color.warn) else color
        c.drawRoundRect(RectF(ox + 2.75f * s, oy + 2.75f * s, ox + (2.75f + w) * s, oy + 9.25f * s), s, s, p)
    }

    override fun getIntrinsicWidth() = (22 * d).toInt()
    override fun getIntrinsicHeight() = (12 * d).toInt()
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The command slip: a small ticket with a dashed edge (waiting) or a solid one (sent, confirmed, not confirmed). */
class SlipDrawable(ctx: Context, var strokeColor: Int, var fillColor: Int, var dashed: Boolean) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val r = RectF(bounds).apply { inset(0.75f * d, 0.75f * d) }
        p.style = Paint.Style.FILL; p.color = fillColor; p.pathEffect = null
        c.drawRoundRect(r, 3 * d, 3 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = strokeColor
        p.pathEffect = if (dashed) DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f) else null
        c.drawRoundRect(r, 3 * d, 3 * d, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
