package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeTest {
    private val now = 1_800_000_000_000L
    private val old = Tracker(name = "Keys", keyHex = "ab".repeat(32), createdAt = now - 86_400_000L)

    @Test fun offeredForOldTagWithoutWelcome() {
        assertTrue(Welcome.needsOffer(old, ownerSet = true, nowMs = now))
        // tags paired before 0.7.5 have createdAt 0: still offered
        assertTrue(Welcome.needsOffer(old.copy(createdAt = 0), true, now))
    }

    @Test fun notOffered() {
        assertFalse(Welcome.needsOffer(old, ownerSet = false, nowMs = now))
        assertFalse(Welcome.needsOffer(old.copy(welcomeSentAt = now - 1000), true, now))
        assertFalse(Welcome.needsOffer(old.copy(welcomeOffered = true), true, now))
        assertFalse(Welcome.needsOffer(old.copy(keyHex = "", sharedBy = "Eva"), true, now))
        // just paired: the wizard's own welcome may still be on its way
        assertFalse(Welcome.needsOffer(old.copy(createdAt = now - 60_000L), true, now))
    }

    @Test fun sentOnlyWhenARelayAccepted() {
        assertNull(Welcome.afterSend(old, 0, now).welcomeSentAt)
        assertEquals(now, Welcome.afterSend(old, 1, now).welcomeSentAt)
        val before = old.copy(welcomeSentAt = 5)
        assertEquals(5L, Welcome.afterSend(before, 0, now).welcomeSentAt) // a failed resend keeps the old success
    }
}
