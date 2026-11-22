package app.libretag.keys

import app.libretag.data.Tracker
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The share link against `share.json` (Python reference, host/src/libretag/share.py). */
class ShareLinkTest {
    private val v: JsonObject = Json.parseToJsonElement(File("../../host/tests/vectors/share.json").readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val keys = Keys(LibreTag.hexToBytes(s("k0"))!!)
    private val e = v["epoch"]!!.jsonPrimitive.int.toLong()

    private fun build(control: Boolean, admin: Boolean) = ShareLink.build(s("name"), s("type"), s("mark"), e, keys.view(e),
        if (control) keys.cmd(e) else null, if (admin) keys.usbKey else null, if (admin) keys.id else null)

    @Test fun buildsTheReferenceLinks() {
        assertEquals(s("view"), build(false, false))
        assertEquals(s("control"), build(true, false))
        assertEquals(s("full"), build(true, true))
    }

    @Test fun roundTripEveryLevel() {
        for ((level, control, admin) in listOf(Triple("view", false, false), Triple("control", true, false), Triple("full", true, true))) {
            val sh = ShareLink.parse(build(control, admin))!!
            assertEquals(level, s("name"), sh.name)
            assertEquals(s("type"), sh.type)
            assertEquals("blue" to "circle", sh.color to sh.symbol)
            assertEquals(e, sh.epoch)
            assertEquals(LibreTag.bytesToHex(keys.view(e)), sh.view)
            assertEquals(if (control) LibreTag.bytesToHex(keys.cmd(e)) else null, sh.cmd)
            assertEquals(control, sh.control)
            assertEquals(admin, sh.admin)
            assertEquals(if (admin) LibreTag.bytesToHex(keys.usbKey) else null, sh.usb)
            assertEquals(if (admin) LibreTag.bytesToHex(keys.id) else null, sh.id)
            // never K0
            assertFalse(build(control, admin).contains(s("k0")))
        }
    }

    @Test fun rejectsBrokenLinks() {
        for (bad in v["invalid"]!!.jsonArray) assertNull(bad.toString(), ShareLink.parse(bad.jsonPrimitive.content))
        assertNull(ShareLink.parse(""))
        assertNull(ShareLink.parse("libretag://share?v=1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fullAccessNeedsControl() {
        ShareLink.build("x", "thing", "", 0, keys.view(0), null, keys.usbKey, keys.id)
    }

    @Test fun importedFullAccessOpensTheConsoleLikeTheOwner() {
        val sh = ShareLink.parse(s("full"))!!
        val t = Tracker(name = sh.name, keyHex = "", sharedBy = "me", sharedControl = sh.control, sharedEpoch = sh.epoch,
            sharedViewHex = sh.view, sharedCmdHex = sh.cmd!!, sharedUsbHex = sh.usb!!, sharedIdHex = sh.id!!)
        assertTrue(t.sharedAdmin && t.hasConsole && t.canCommand)
        val ck = ConsoleKey.shared(t)
        assertNotNull(ck)
        val owner = ConsoleKey.of(keys)
        assertArrayEquals(owner.usbKey, ck!!.usbKey)
        assertArrayEquals(owner.id, ck.id)
        // the USB unlock answer is the owner's
        val challenge = ByteArray(16) { it.toByte() }
        assertArrayEquals(LibreTag.usbAuth(keys.usbKey, challenge), LibreTag.usbAuth(ck.usbKey, challenge))
        // view and control: no console
        val ctl = t.copy(sharedUsbHex = "", sharedIdHex = "")
        assertFalse(ctl.sharedAdmin || ctl.hasConsole)
        assertNull(ConsoleKey.shared(ctl))
        // the owner's own tag has the console, a shared one never by its (empty) K0
        assertTrue(Tracker(name = "own", keyHex = s("k0")).hasConsole)
        assertFalse(Tracker(name = "own", keyHex = "").hasConsole)
    }
}
