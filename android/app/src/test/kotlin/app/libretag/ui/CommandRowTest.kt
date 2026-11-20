package app.libretag.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The command buttons never break a word (owner request, app 0.12): the labels are measured with a
 * sans-serif font at 15 sp, at font scale 1.0 and 1.3, on a 360 dp screen (328 dp inside the
 * detail's padding), for every language. A label must fit a row on its own, and the row plan
 * wraps instead of squeezing.
 */
class CommandRowTest {
    @Test fun oneRowWhenEveryLabelFitsItsSlot() {
        assertEquals(listOf(listOf(0, 1, 2)), CommandRow.rows(listOf(90, 100, 80), 328, 8))
    }

    @Test fun wrapsWhenALabelIsTooWideForAThird() {
        // 328 - 16 = 312, a slot is 104: 150 does not fit, so pack: 100 + 8 + 150 = 258, then 90
        assertEquals(listOf(listOf(0, 1), listOf(2)), CommandRow.rows(listOf(100, 150, 90), 328, 8))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), CommandRow.rows(listOf(300, 300, 300), 328, 8))
    }

    @Test fun stretchFillsTheRow() {
        assertEquals(listOf(104, 104, 104), CommandRow.stretch(listOf(50, 60, 70), 328, 8, equal = true))
        val w = CommandRow.stretch(listOf(100, 150), 328, 8, equal = false)
        assertEquals(320, w.sum())
    }

    private val res = File("src/main/res")
    private fun label(dir: String, name: String): String {
        val xml = File(res, "$dir/strings.xml").readText()
        return Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1].replace("\\'", "'")
    }

    /** the command button's padding 12 dp + 14 dp, icon 18 dp and its padding 8 dp (TagBodyView.bindCommands) */
    private val chrome = 52

    /** Roboto Medium advance widths in em, rounded up (narrow letters, capitals, digits, the rest) */
    private fun em(c: Char): Double = when {
        c == ' ' -> 0.26
        c in "iljtfrI.,'" -> 0.34
        c in "mwMW" -> 0.88
        c.isUpperCase() -> 0.68
        c.isDigit() -> 0.58
        else -> 0.58
    }

    private fun width(s: String, scale: Double) = (s.sumOf { em(it) } * 15 * scale).toInt() + 1 + chrome

    @Test fun everyLanguageFitsWithoutBreakingAWord() {
        val avail = 360 - 32
        for (dir in listOf("values", "values-sk", "values-cs", "values-de", "values-es", "values-nl")) {
            val labels = listOf("cmd_lost", "cmd_track", "cmd_beep").map { label(dir, it) }
            for (scale in listOf(1.0, 1.3)) {
                val ws = labels.map { width(it, scale) }
                ws.forEachIndexed { i, w -> assertTrue("$dir ${labels[i]} at $scale: $w dp", w <= avail) }
                val rows = CommandRow.rows(ws, avail, 8)
                // every button gets at least its natural width
                for (row in rows) {
                    val got = CommandRow.stretch(row.map { ws[it] }, avail, 8, equal = CommandRow.equal(ws, avail, 8))
                    row.forEachIndexed { j, i -> assertTrue("$dir ${labels[i]} squeezed at $scale", got[j] >= ws[i]) }
                }
            }
        }
    }
}
