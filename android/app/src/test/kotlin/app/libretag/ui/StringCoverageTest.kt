package app.libretag.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every translatable string and plural in values/strings*.xml exists in every locale folder. */
class StringCoverageTest {
    private val res = File("src/main/res")
    private val locales = listOf("values-sk", "values-cs", "values-de", "values-es", "values-nl")

    private fun keys(dir: String, onlyTranslatable: Boolean): Set<String> {
        val out = HashSet<String>()
        val files = File(res, dir).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: return out
        for (f in files) {
            Regex("<(string|plurals)\\s+([^>]*)>").findAll(f.readText()).forEach { m ->
                val attrs = m.groupValues[2]
                if (onlyTranslatable && Regex("translatable\\s*=\\s*\"false\"").containsMatchIn(attrs)) return@forEach
                Regex("name\\s*=\\s*\"([^\"]+)\"").find(attrs)?.let { out += it.groupValues[1] }
            }
        }
        return out
    }

    @Test fun everyLocaleHasEveryKey() {
        val base = keys("values", true)
        assertTrue("no base strings found", base.size > 100)
        val problems = locales.mapNotNull { l ->
            val missing = (base - keys(l, false)).sorted()
            if (missing.isEmpty()) null else "$l is missing ${missing.size}: ${missing.joinToString(", ")}"
        }
        assertTrue("Untranslated keys:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
