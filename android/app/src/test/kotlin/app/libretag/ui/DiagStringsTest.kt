package app.libretag.ui

import app.libretag.data.Diagnosis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every cause has a row sentence and a card in every language, the strings files agree on names
 * and placeholders, and a row sentence with its values filled in fits a list row (44 characters,
 * docs/design/tag-diagnosis.md 9, step 7).
 */
class DiagStringsTest {
    private val res = File("src/main/res")
    private val langs = listOf("values", "values-sk", "values-cs", "values-de", "values-es", "values-nl")

    /** name -> texts (one for a string, every item for a plural) */
    private fun load(dir: String, file: String = "strings_diag.xml"): Map<String, List<String>> {
        val xml = File(res, "$dir/$file").readText()
        val out = LinkedHashMap<String, List<String>>()
        Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(xml).forEach { out[it.groupValues[1]] = listOf(it.groupValues[2]) }
        Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            out[m.groupValues[1]] = Regex("<item quantity=\"[a-z]+\">([^<]*)</item>").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        return out
    }

    private fun args(s: String) = Regex("""%(\d)[$][sd]""").findAll(s).map { it.value }.toSet()

    @Test fun everyCauseHasARowAndACard() {
        val en = load("values") + load("values", "strings.xml") + load("values", "strings_status.xml")
        for (c in Diagnosis.Cause.entries) {
            assertTrue("row for $c", DiagText.ROW.getValue(c) in en)
            assertTrue("card for $c", DiagText.CARD.getValue(c) in en)
        }
    }

    @Test fun languagesAgree() {
        val en = load("values")
        for (dir in langs.drop(1)) {
            val l = load(dir)
            assertEquals("$dir names", en.keys, l.keys)
            for ((k, v) in en) {
                val want = v.flatMap { args(it) }.toSet()
                val got = l.getValue(k).flatMap { args(it) }.toSet()
                assertTrue("$dir $k placeholders $got vs $want", want.containsAll(got) && (got.containsAll(want) || v.size > 1))
                assertTrue("$dir $k em dash", l.getValue(k).none { '—' in it })
            }
        }
    }

    @Test fun rowSentencesFitARow() {
        val sample = listOf("14:31", "3 h", "Home")
        for (dir in langs) {
            for ((k, v) in load(dir).filterKeys { it.startsWith("dg_row_") }) for (text in v) {
                var s = text.replace("\\'", "'")
                s = s.replace(Regex("""%(\d)[$]d"""), "4")
                s = s.replace(Regex("""%(\d)[$]s""")) { m ->
                    when {
                        k == "dg_row_battery_empty" && m.groupValues[1] == "1" -> "4 %"
                        k == "dg_row_firmware_too_old" -> "0.8.2"
                        k == "dg_row_schedule_mismatch" -> "every 2 h"
                        k == "dg_row_maybe_quiet_place" && m.groupValues[1] == "2" -> sample[2]
                        k.startsWith("dg_row_out_of_range") || k.startsWith("dg_row_maybe") -> sample[1]
                        else -> sample[0]
                    }
                }
                assertTrue("$dir $k is ${s.length} chars: $s", s.length <= 44)
            }
        }
    }
}
