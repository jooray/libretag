package app.libretag

import app.libretag.nostr.Bech32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NpubTest {
    // NIP-19 example key
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
    private val nprofile = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"

    private fun n(s: String) = Bech32.normalizeNpub(s)

    @Test fun npubForms() {
        assertEquals(npub, n(npub))
        assertEquals(npub, n("  nostr:$npub\n"))
        assertEquals(npub, n(npub.uppercase())) // QR alphanumeric mode
        assertEquals(npub, n(hex))
        assertEquals(npub, n("nostr:$hex"))
    }

    @Test fun nprofile() {
        assertEquals(npub, n(nprofile))
        assertEquals(npub, n("nostr:$nprofile"))
    }

    @Test fun urls() {
        assertEquals(npub, n("https://njump.me/$npub"))
        assertEquals(npub, n("https://njump.me/$nprofile"))
        assertEquals(npub, n("https://yakihonne.com/profile/$nprofile?tab=notes"))
        assertEquals(npub, n("https://yakihonne.com/users/$npub"))
        assertEquals(npub, n("https://example.org/p/$hex"))
    }

    @Test fun rejectsGarbage() {
        assertNull(n("https://example.com"))
        assertNull(n(npub.dropLast(1) + if (npub.last() == 'q') "p" else "q"))
        assertNull(n(Bech32.encode("nsec", ByteArray(32) { it.toByte() })))
        assertNull(n(hex + "00")) // 66 hex chars: not a key
    }
}
