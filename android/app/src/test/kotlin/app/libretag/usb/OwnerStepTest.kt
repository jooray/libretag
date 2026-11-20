package app.libretag.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pairing wizard's owner step and its repair on later USB sessions (0.8.8). */
class OwnerStepTest {
    private val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0v8ycx"

    @Test fun theAnswerEqualToTheCommandIsNotTakenForTheEcho() {
        // `owner <npub>` is echoed, then answered with exactly the same line (print_nostr)
        val raw = listOf("owner $npub", "NOSTR NPUB npub1tag", "owner $npub", "low-battery DM (below 10 %) on | clock ok")
        val got = TrackerConsole.withoutEcho(raw, "owner $npub")
        assertEquals(listOf("NOSTR NPUB npub1tag", "owner $npub", "low-battery DM (below 10 %) on | clock ok"), got)
        assertEquals(true, got.any { it.startsWith("owner npub1") })
        // no echo at all: nothing removed
        assertEquals(listOf("OK"), TrackerConsole.withoutEcho(listOf("OK"), "network auto"))
    }

    private fun status(owner: String?, unlocked: Boolean = true) = TrackerStatus.parse(listOfNotNull(
        "LIBRETAG ID 0011223344556677",
        "locked (unlocked for this connection)",
        if (unlocked) "radio 869.618 MHz bw=62.5 sf=7 cr=5 pwr=22 dBm path=2B" else "battery 80%",
        if (unlocked) (owner?.let { "owner $it" } ?: "owner none (DMs off): owner <npub|hex>") else null,
    ))

    @Test fun anUnlockedTagWithoutAnOwnerGetsTheAppsNpub() {
        val cmds = setOf("status", "owner", "nostr")
        assertEquals(npub, UsbTagSession.ownerToSend(status(null), npub, cmds))
        assertNull(UsbTagSession.ownerToSend(status(npub), npub, cmds))                 // already set
        assertNull(UsbTagSession.ownerToSend(status(null), null, cmds))                 // the app has none
        assertNull(UsbTagSession.ownerToSend(status(null, unlocked = false), npub, cmds)) // locked status
        assertNull(UsbTagSession.ownerToSend(status(null), npub, setOf("status")))      // firmware without Nostr
    }
}
