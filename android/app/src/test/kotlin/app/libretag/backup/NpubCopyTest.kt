package app.libretag.backup

import app.libretag.nostr.Nip44
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The npub copy against a mock signer that does what Amber or a bunker does: NIP-44 to self, BIP-340. */
class NpubCopyTest {
    private class MockSigner(val sk: ByteArray) : OwnerSigner {
        override val pubkeyHex = NostrEvent.xonly(sk).hex()
        var decrypts = 0
        var refuse = false
        override suspend fun nip44Encrypt(plaintext: String, peerHex: String): String {
            if (refuse) throw SignerException(SignerException.Reason.REJECTED)
            return Nip44.encrypt(plaintext, Nip44.conversationKey(sk, peerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        }
        override suspend fun nip44Decrypt(payload: String, peerHex: String): String {
            decrypts++
            if (refuse) throw SignerException(SignerException.Reason.REJECTED)
            return try {
                Nip44.decrypt(payload, Nip44.conversationKey(sk, peerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
            } catch (e: Nip44.Nip44Exception) {
                throw SignerException(SignerException.Reason.BAD_ANSWER)
            }
        }
        override suspend fun signEvent(event: NostrEvent): NostrEvent =
            NostrEvent.sign(sk, event.createdAt, event.kind, event.tags, event.content)
    }

    private val owner = MockSigner(ByteArray(32) { 0x21 })
    private val d = "00112233445566778899aabbccddeeff"
    private val payload = BackupPayload(created = 1_800_000_000, owner = null, d = d, tags = listOf(
        BackupTag("Car", "ab".repeat(32), "vehicle", "red-stripe", updated = 1_800_000_000),
    ))

    @Test fun sealHasNoMarkerAndRoundTrips() = runBlocking {
        val ev = NpubCopy.seal(owner, payload, now = 1_800_000_100)
        assertEquals(30078, ev.kind)
        assertEquals(owner.pubkeyHex, ev.pubkey)
        assertEquals(listOf(listOf("d", d)), ev.tags) // no alt, nothing that names LibreTag
        assertFalse(ev.toJson().toString().contains("LibreTag", ignoreCase = true))
        assertTrue(ev.verify())
        assertTrue(Nip44.isPayload(ev.content))
        assertEquals(payload, NpubCopy.open(owner, ev))
        assertEquals(NpubCopy.Found.Backup(payload, ev), NpubCopy.read(owner, d, listOf(ev)))
    }

    @Test fun discoveryAmongOtherAppData() = runBlocking {
        val ours = NpubCopy.seal(owner, payload, now = 1_800_000_100)
        // other apps' kind 30078: plain JSON, a NIP-44 payload that isn't ours, a newer copy of ours elsewhere
        val plain = owner.signEvent(NostrEvent(owner.pubkeyHex, 1_800_000_500, 30078, listOf(listOf("d", "settings")), "{\"theme\":\"dark\"}").withId())
        val otherNip44 = owner.signEvent(NostrEvent(owner.pubkeyHex, 1_800_000_400, 30078, listOf(listOf("d", "drafts")),
            owner.nip44Encrypt("{\"draft\":1}", owner.pubkeyHex)).withId())
        val stranger = MockSigner(ByteArray(32) { 0x33 })
        val notOwner = NpubCopy.seal(stranger, payload, now = 1_800_000_900)
        // payload whose d doesn't match its event's d tag (copied under another d): refused
        val moved = owner.signEvent(NostrEvent(owner.pubkeyHex, 1_800_000_300, 30078, listOf(listOf("d", "ff".repeat(16))), ours.content).withId())
        val events = listOf(plain, otherNip44, notOwner, moved, ours)
        val found = NpubCopy.discover(owner, events)
        assertEquals(NpubCopy.Found.Backup(payload, ours), found)
        // plain JSON and the stranger's event were never sent to the signer
        assertEquals(3, owner.decrypts)
        assertEquals(listOf(otherNip44, moved, ours), NpubCopy.candidates(owner.pubkeyHex, events))
    }

    @Test fun newestPerDWinsAndDeletionSticks() = runBlocking {
        val old = NpubCopy.seal(owner, payload, now = 1_800_000_100)
        val newer = payload.copy(tags = payload.tags.map { it.copy(name = "Car 2", updated = 1_800_000_200) })
        val new = NpubCopy.seal(owner, newer, now = 1_800_000_200)
        assertEquals(NpubCopy.Found.Backup(newer, new), NpubCopy.discover(owner, listOf(old, new)))
        val del = NpubCopy.deletion(owner, d, now = 1_800_000_300)
        assertEquals("", del[0].content)
        assertEquals(listOf(listOf("d", d)), del[0].tags)
        assertEquals(5, del[1].kind)
        assertEquals(listOf("a", "30078:${owner.pubkeyHex}:$d"), del[1].tags[0])
        assertEquals(NpubCopy.Found.Deleted, NpubCopy.read(owner, d, listOf(old, new) + del))
        assertEquals(NpubCopy.Found.None, NpubCopy.discover(owner, listOf(old, new) + del))
    }

    @Test fun atMostTenCandidates() = runBlocking {
        val events = (0 until 15).map { i ->
            owner.signEvent(NostrEvent(owner.pubkeyHex, 1_800_000_000L + i, 30078, listOf(listOf("d", "x$i")),
                owner.nip44Encrypt("{\"other\":$i}", owner.pubkeyHex)).withId())
        } + NpubCopy.seal(owner, payload, now = 1_700_000_000) // oldest: beyond the first ten
        assertEquals(NpubCopy.Found.None, NpubCopy.discover(owner, events))
        assertEquals(10, owner.decrypts)
    }

    @Test fun refusalStopsTheSearch() = runBlocking {
        val ev = NpubCopy.seal(owner, payload, now = 1_800_000_100)
        owner.refuse = true
        try {
            NpubCopy.discover(owner, listOf(ev))
            fail("expected SignerException")
        } catch (e: SignerException) {
            assertEquals(SignerException.Reason.REJECTED, e.reason)
        }
    }

    @Test fun wrongSignatureFromSignerIsRefused() = runBlocking {
        val liar = object : OwnerSigner by owner {
            override suspend fun signEvent(event: NostrEvent) = event.copy(sig = "00".repeat(64))
        }
        try {
            NpubCopy.seal(liar, payload)
            fail("expected SignerException")
        } catch (e: SignerException) {
            assertEquals(SignerException.Reason.BAD_ANSWER, e.reason)
        }
    }

    @Test fun bookTracksEditsAndRemovals() {
        val now = 1_800_000_000L
        val car = BackupTag("Car", "ab".repeat(32), "vehicle", "red-stripe")
        val bag = BackupTag("Bag", "cd".repeat(32), "thing", "blue-triangle")
        var b = BackupBook().track(listOf(car, bag), now) { if (it == car) now - 500 else null }
        assertEquals(now - 500, b.seen["ab".repeat(32)]!!.second)
        assertEquals(now, b.seen["cd".repeat(32)]!!.second)
        b = b.track(listOf(car.copy(name = "Auto"), bag), now + 10)
        assertEquals(now + 10, b.seen["ab".repeat(32)]!!.second)
        assertEquals(now, b.seen["cd".repeat(32)]!!.second)
        b = b.track(listOf(car.copy(name = "Auto")), now + 20)
        assertEquals(mapOf("cd".repeat(32) to now + 20), b.removed)
        val e = b.entries(listOf(car.copy(name = "Auto")))
        assertEquals(listOf(car.copy(name = "Auto", updated = now + 10), BackupTag.tombstone("cd".repeat(32), now + 20)), e)
        // undo: back, newer than its tombstone
        b = b.track(listOf(car.copy(name = "Auto"), bag), now + 30)
        assertTrue(b.removed.isEmpty())
        assertEquals(now + 30, b.seen["cd".repeat(32)]!!.second)
        // tombstones expire
        b = b.track(listOf(bag), now + 40).track(listOf(bag), now + 40 + BackupMerge.TOMBSTONE_KEEP_S)
        assertTrue(b.removed.isEmpty())
    }
}
