package app.libretag.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The command buttons (Lost / Track / Beep): one row of equal buttons when every label fits on
 * one line at that width, else as many rows as needed, each button as wide as its label and the
 * row stretched to the full width. Labels never break inside a word (no hyphenation, one line).
 */
class CommandRow(ctx: Context) : ViewGroup(ctx) {
    var gap: Int = ctx.dp(8)

    override fun addView(child: View, index: Int, params: LayoutParams?) {
        (child as? TextView)?.let { tv ->
            tv.maxLines = 1
            tv.isSingleLine = true
            tv.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
            tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            tv.ellipsize = null
        }
        super.addView(child, index, params ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private var plan: List<List<Int>> = emptyList()
    private val widths = mutableListOf<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val kids = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val natural = kids.map { it.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec); it.measuredWidth }
        plan = rows(natural, avail, gap)
        val eq = equal(natural, avail, gap)
        widths.clear()
        var h = paddingTop + paddingBottom
        plan.forEachIndexed { ri, row ->
            val ws = stretch(row.map { natural[it] }, avail, gap, equal = eq)
            var rowH = 0
            row.forEachIndexed { j, i ->
                val c = kids[i]
                c.measure(MeasureSpec.makeMeasureSpec(ws[j], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                rowH = maxOf(rowH, c.measuredHeight)
            }
            h += rowH + if (ri > 0) gap else 0
            widths += ws
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val kids = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        var y = paddingTop
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (row in plan) {
            var x = if (rtl) width - paddingRight else paddingLeft
            var rowH = 0
            for (i in row) {
                val c = kids[i]
                if (rtl) { c.layout(x - c.measuredWidth, y, x, y + c.measuredHeight); x -= c.measuredWidth + gap }
                else { c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight); x += c.measuredWidth + gap }
                rowH = maxOf(rowH, c.measuredHeight)
            }
            y += rowH + gap
        }
    }

    companion object {
        /**
         * Which children go on which row: all on one row when n equal slots each hold the widest
         * label, else packed greedily by natural width (a label wider than the row gets a row alone).
         */
        /** n equal slots each hold the widest label */
        fun equal(natural: List<Int>, avail: Int, gap: Int): Boolean {
            val n = natural.size
            if (n == 0) return false
            val slot = (avail - gap * (n - 1)) / n
            return natural.all { it <= slot }
        }

        fun rows(natural: List<Int>, avail: Int, gap: Int): List<List<Int>> {
            if (natural.isEmpty()) return emptyList()
            if (equal(natural, avail, gap)) return listOf(natural.indices.toList())
            val out = mutableListOf<MutableList<Int>>()
            var used = 0
            for (i in natural.indices) {
                val w = natural[i]
                val cur = out.lastOrNull()
                if (cur != null && used + gap + w <= avail) { cur += i; used += gap + w }
                else { out += mutableListOf(i); used = w }
            }
            return out
        }

        /** The widths in one row: equal slots, or each natural width plus an equal share of what is left. */
        fun stretch(natural: List<Int>, avail: Int, gap: Int, equal: Boolean): List<Int> {
            val n = natural.size
            val room = avail - gap * (n - 1)
            if (equal) return List(n) { room / n }
            val extra = (room - natural.sum()).coerceAtLeast(0) / n
            return natural.map { minOf(it + extra, room) }
        }
    }
}
