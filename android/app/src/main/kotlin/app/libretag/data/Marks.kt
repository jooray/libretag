package app.libretag.data

/**
 * Trail blazes: 4 marking colours × 5 hiking-trail symbols. Shape and colour together tell tags
 * apart, so colour alone never carries meaning (colour-blind safe).
 */
object Marks {
    val COLORS = listOf("red", "blue", "green", "yellow")
    val SYMBOLS = listOf("stripe", "diag", "triangle", "circle", "square")

    /** First free mark, by symbol then colour (stripe in all colours first), like the prototype. */
    fun free(used: Collection<Pair<String, String>>): Pair<String, String> {
        val u = used.toSet()
        for (s in SYMBOLS) for (c in COLORS) if ((c to s) !in u) return c to s
        return "yellow" to "triangle"
    }
}
