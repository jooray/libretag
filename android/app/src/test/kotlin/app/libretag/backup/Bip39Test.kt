package app.libretag.backup

import app.libretag.nostr.hex
import app.libretag.nostr.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class Bip39Test {
    /** Official BIP-39 test vectors (trezor/python-mnemonic vectors.json, English): entropy to words. */
    private val official = listOf(
        "00000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to "legal winner thank year wave sausage worth useful legal winner thank yellow",
        "80808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
        "ffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
        "000000000000000000000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon agent",
        "0000000000000000000000000000000000000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
        "8080808080808080808080808080808080808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
        "9e885d952ad362caeb4efe34a8e91bd2" to "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
        "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c" to "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
        "c0ba5a8e914111210f2bd131f3d5e08d" to "scheme spot photo card baby mountain device kick cradle pact join borrow",
        "23db8160a31d3e0dca3688ed941adbf3" to "cat swing flag economy stadium alone churn speed unique patch report train",
        "f30f8c1da665478f49b001d94c5fc452" to "vessel ladder alter error federal sibling chat ability sun glass valve picture",
        "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f" to "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold",
    )

    @Test fun wordlistIsTheOfficialOne() {
        val raw = File("src/main/resources/app/libretag/backup/bip39-english.txt").readBytes()
        assertEquals(Bip39.WORDLIST_SHA256, MessageDigest.getInstance("SHA-256").digest(raw).hex())
        assertEquals(2048, Bip39.words.size)
        assertEquals("abandon", Bip39.words.first())
        assertEquals("zoo", Bip39.words.last())
    }

    @Test fun officialVectors() {
        for ((e, m) in official) {
            assertEquals(m, Bip39.fromEntropy(e.unhex()!!))
            assertEquals(e, Bip39.toEntropy(m)!!.hex())
        }
    }

    @Test fun checkAcceptsTwelveAndTwentyFour() {
        for ((e, m) in official) {
            val n = m.split(' ').size
            val r = Bip39.check(m)
            if (n == 12 || n == 24) assertEquals(Bip39.Check.Ok(m), r)
            else assertEquals(e, Bip39.Problem.WRONG_LENGTH, (r as Bip39.Check.Bad).problem)
        }
    }

    @Test fun normalisesCaseAndSpacing() {
        val m = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertEquals(Bip39.Check.Ok(m), Bip39.check("  Legal  WINNER\tthank year wave sausage worth useful legal winner thank yellow\n"))
        // fullwidth letters fold under NFKC
        assertEquals(Bip39.Check.Ok(m), Bip39.check("ｌｅｇａｌ winner thank year wave sausage worth useful legal winner thank yellow"))
    }

    @Test fun refusesBadInput() {
        fun problem(s: String) = (Bip39.check(s) as Bip39.Check.Bad).problem
        assertEquals(Bip39.Problem.EMPTY, problem("   "))
        assertEquals(Bip39.Problem.BAD_CHECKSUM, problem(List(12) { "abandon" }.joinToString(" ")))
        assertEquals(Bip39.Problem.WRONG_LENGTH, problem(List(11) { "abandon" }.joinToString(" ")))
        val unknown = Bip39.check("abandon abandon bitcoin abandon abandon abandon abandon abandon abandon abandon abandon about") as Bip39.Check.Bad
        assertEquals(Bip39.Problem.UNKNOWN_WORD, unknown.problem)
        assertEquals("bitcoin", unknown.word)
        // a free-form passphrase, even a long one, is refused
        assertTrue(Bip39.check("Granite owl counts seven figs") is Bip39.Check.Bad)
        assertNull(KeyBackup.deriveFromPhrase("Granite owl counts seven figs"))
    }

    @Test fun generatesValidTwelveWordPhrases() {
        val r = SecureRandom()
        val seen = HashSet<String>()
        repeat(200) {
            val m = Bip39.generate(r)
            assertEquals(12, m.split(' ').size)
            assertEquals(Bip39.Check.Ok(m), Bip39.check(m))
            seen += m
        }
        assertEquals(200, seen.size)
        assertNotEquals(Bip39.generate(), Bip39.generate())
    }
}
