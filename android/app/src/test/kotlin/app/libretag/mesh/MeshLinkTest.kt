package app.libretag.mesh

import io.github.jooray.meshcore.localapi.ClientIdentity
import io.github.jooray.meshcore.localapi.Pairing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The app's side of the MeshCore Local API library: stored pairing, cursor, channel and type. */
class MeshLinkTest {
    private class Settings(override var meshIdentity: String? = null) : MeshSettings {
        override val meshUrl = "ws://127.0.0.1:8787/api/v1"
        override val meshRegion = ""
        override var meshCursor: String? = null
    }

    /** What MeshCoreApiClient wrote before 0.13: PKCS#8 private key and raw X||Y, base64 without wrapping. */
    private fun legacyIdentity(): Pair<String, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val w = (pair.public as ECPublicKey).w
        fun fixed(b: ByteArray) = (if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b)
        val raw = fixed(w.affineX.toByteArray()) + fixed(w.affineY.toByteArray())
        val enc = Base64.getEncoder()
        return """{"private":"${enc.encodeToString(pair.private.encoded)}","public":"${enc.encodeToString(raw)}"}""" to raw
    }

    @Test fun anUpdateKeepsThePairingKey() {
        val (stored, raw) = legacyIdentity()
        val settings = Settings(stored)
        val id = ClientIdentity.loadOrCreate(SettingsKeyStore(settings))
        assertArrayEquals(raw, id.publicKeyRaw)
        assertEquals(stored, settings.meshIdentity) // not replaced by a new key
        val fp = MessageDigest.getInstance("SHA-256").digest(raw).copyOf(8).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
        assertEquals(fp, id.fingerprint)
    }

    @Test fun aFirstRunStoresANewKey() {
        val settings = Settings()
        val id = ClientIdentity.loadOrCreate(SettingsKeyStore(settings))
        assertNotNull(settings.meshIdentity)
        assertArrayEquals(id.publicKeyRaw, ClientIdentity.decode(settings.meshIdentity!!)!!.publicKeyRaw)
    }

    @Test fun theCursorLivesInTheSettings() {
        val settings = Settings()
        val cursors = SettingsCursorStore(settings)
        assertNull(cursors.load())
        cursors.save("2026-11-20T10:00:00Z ab12")
        assertEquals("2026-11-20T10:00:00Z ab12", settings.meshCursor)
    }

    @Test fun reportsAndCommandsOnLibretag() {
        val c = MeshLink.config("ws://127.0.0.1:8787/api/v1")
        assertEquals("#libretag", c.channel)
        assertEquals(listOf(0xFF4C), c.dataTypes)
        assertEquals("LibreTag", c.clientName)
    }

    @Test fun onlyTheNewPairingStringIsSigned() {
        val nonce = "AAAAAAAAAAAAAAAAAAAAAA"
        fun challenge(message: String) = buildJsonObject { put("type", "pairing.challenge"); put("nonce", nonce); put("message", JsonPrimitive(message)) }
        assertEquals("meshcore-local-api-pair-v1:$nonce", Pairing.messageToSign(challenge("meshcore-local-api-pair-v1:$nonce")))
        assertNull(Pairing.messageToSign(challenge("meshcore-open-pair-v1:$nonce")))
    }
}
