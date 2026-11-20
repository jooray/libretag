package app.libretag.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the shared vectors (copied from the support widget spec, engine-vectors.json; refresh
 * the copy whenever the spec changes) against the Kotlin port of the engine.
 */
class SupportEngineTest {

    private val vectors = Json.parseToJsonElement(javaClass.getResource("/support/engine-vectors.json")!!.readText()).jsonObject

    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.boolean ?: false

    private fun trigger(o: JsonObject) = SupportEngine.Trigger(
        minDays = o["minDays"]!!.jsonPrimitive.int,
        minActions = o["minActions"]!!.jsonPrimitive.int,
        cooldownDays = o["cooldownDays"]!!.jsonArray.map { it.jsonPrimitive.int },
        maxAsks = o["maxAsks"]!!.jsonPrimitive.int,
    )

    private fun context(o: JsonObject) = SupportEngine.Context(
        today = o["today"]!!.jsonPrimitive.content,
        momentOfValue = o.bool("momentOfValue"),
        busy = o.bool("busy"),
        isSupporter = o.bool("isSupporter"),
    )

    /** Replays one case; returns the failed ask steps. */
    private fun replay(c: JsonObject, trigger: SupportEngine.Trigger): List<String> {
        var s = SupportEngine.State()
        val failures = mutableListOf<String>()
        for (el in c["steps"]!!.jsonArray) {
            val step = el.jsonArray
            when (val op = step[0].jsonPrimitive.content) {
                "launch" -> s = SupportEngine.onLaunch(s, step[1].jsonPrimitive.content)
                "action" -> repeat(step[1].jsonPrimitive.int) { s = SupportEngine.onAction(s) }
                "shown" -> s = SupportEngine.onShown(s, step[1].jsonPrimitive.content)
                "tapped" -> s = SupportEngine.onSupportTapped(s)
                "declined" -> s = SupportEngine.onDeclined(s)
                "ask" -> {
                    val ctx = context(step[1].jsonObject)
                    val got = SupportEngine.shouldAsk(s, ctx, trigger)
                    if (got != step[2].jsonPrimitive.boolean) failures += "${c["name"]} @ ${ctx.today}: got $got"
                }
                else -> error("unknown op $op")
            }
        }
        return failures
    }

    @Test
    fun everySharedVectorPasses() {
        val trigger = trigger(vectors["trigger"]!!.jsonObject)
        val cases = vectors["cases"]!!.jsonArray
        assertTrue("vectors file looks truncated", cases.size >= 14)
        assertEquals(emptyList<String>(), cases.flatMap { replay(it.jsonObject, trigger) })
    }

    @Test
    fun libreTagWaitsForSevenDaysAndTenActions() {
        var s = SupportEngine.State()
        for (d in 1..6) s = SupportEngine.onLaunch(s, "2026-11-0$d")
        repeat(20) { s = SupportEngine.onAction(s) }
        val ctx = SupportEngine.Context(today = "2026-11-06", momentOfValue = true)
        assertFalse(SupportEngine.shouldAsk(s, ctx, SupportEngine.LIBRE_TAG))
        s = SupportEngine.onLaunch(s, "2026-11-07")
        assertTrue(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-07"), SupportEngine.LIBRE_TAG))
        // a tag in Lost / Alarm / Silent state is "busy"
        assertFalse(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-07", busy = true), SupportEngine.LIBRE_TAG))

        var few = SupportEngine.State()
        for (d in 1..9) few = SupportEngine.onLaunch(few, "2026-11-0$d")
        repeat(9) { few = SupportEngine.onAction(few) }
        assertFalse(SupportEngine.shouldAsk(few, ctx.copy(today = "2026-11-09"), SupportEngine.LIBRE_TAG))
        assertTrue(SupportEngine.shouldAsk(SupportEngine.onAction(few), ctx.copy(today = "2026-11-09"), SupportEngine.LIBRE_TAG))
    }

    @Test
    fun storedStateSurvivesARoundTripAndGarbageIsAFreshStart() {
        val s = SupportEngine.State(activeDays = 6, lastActiveDay = "2026-11-15", actions = 12, asks = 1, lastAskDay = "2026-11-10", declined = true)
        assertEquals(s, SupportState.decode(SupportState.encode(s)))
        assertEquals(SupportEngine.State(), SupportState.decode(SupportState.encode(SupportEngine.State())))
        assertEquals(SupportEngine.State(), SupportState.decode("{not json"))
        assertEquals(SupportEngine.State(), SupportState.decode("[1,2]"))
        assertEquals(SupportEngine.State(), SupportState.decode(null))
        assertEquals(SupportEngine.State(), SupportState.decode(""))
    }

    @Test
    fun checkoutUrlCarriesLangThemeAndRefOnly() {
        assertEquals(
            "https://hackyourself.io/support/s/libre-tag?lang=sk&theme=dark&ref=command-confirmed",
            SupportCheckout.url(SupportCheckout.REF_COMMAND_CONFIRMED, dark = true, language = "sk"),
        )
        assertEquals(
            "https://hackyourself.io/support/s/libre-tag?lang=cs&theme=light&ref=paired",
            SupportCheckout.url(SupportCheckout.REF_PAIRED, dark = false, language = "cs"),
        )
        assertEquals(
            "https://hackyourself.io/support/s/libre-tag?lang=en&theme=light&ref=menu",
            SupportCheckout.url(SupportCheckout.REF_MENU, dark = false, language = "de"),
        )
    }
}
