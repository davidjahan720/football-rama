package fr.footballrama.core

import fr.footballrama.core.fixtures.FixturesException
import fr.footballrama.core.fixtures.FixturesException.Reason
import fr.footballrama.core.fixtures.FixturesVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FixturesVerifierTest {

    private val signer = TestSigner()
    private val now = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
    private val verifier = FixturesVerifier(listOf(signer.publicKeyBase64), now)

    private fun doc(generatedAt: String = "2026-10-20T06:00:00Z", validUntil: String = "2026-10-21T18:00:00Z", schema: Int = 1) =
        """{"schema":$schema,"generated_at":"$generatedAt","valid_until":"$validUntil",
           "window":{"from":"2026-10-20T00:00:00Z","to":"2026-10-22T03:00:00Z"},
           "competitions":{"CL":"Ligue des champions"},"unknown_field":{"x":[1,2]},
           "matching":{"competition_keywords":{"CL":["champions league","ucl"]},"excluded_title_words":["youth league"]},
           "matches":[{"id":551234,"competition":"CL","kickoff_utc":"2026-10-20T19:00:00Z","extra":true,
             "home":{"name":"Paris Saint-Germain FC","short":"PSG","aliases":["paris saint germain","psg","p"],"tokens":[]},
             "away":{"name":"FC Barcelona","short":"Barça","aliases":["barcelona","Barça"]}}]}""".toByteArray()

    @Test
    fun `signature valide, cles inconnues ignorees`() {
        val json = doc()
        val f = verifier.verify(json, signer.sign(json), null)
        assertEquals(1, f.matches.size)
        assertEquals(Instant.parse("2026-10-20T19:00:00Z"), f.matches[0].kickoff)
        // Alias d'1 caractere ecarte, alias re-normalise ("Barça" -> "barca"), tokens absent = vide.
        assertEquals(listOf("paris saint germain", "psg"), f.matches[0].home.aliases)
        assertEquals(listOf("barcelona", "barca"), f.matches[0].away.aliases)
        assertTrue(f.matches[0].away.tokens.isEmpty())
    }

    @Test
    fun `signature avec retour a la ligne et espaces acceptee (trim)`() {
        val json = doc()
        val sig = ("  " + String(signer.sign(json)) + "\r\n").toByteArray()
        verifier.verify(json, sig, null)
    }

    @Test
    fun `signature invalide - octets modifies, autre cle, base64 casse`() {
        val json = doc()
        val sig = signer.sign(json)
        val tampered = json.copyOf().also { it[it.size - 3] = '0'.code.toByte() }
        assertEquals(Reason.BAD_SIGNATURE, assertThrows<FixturesException> { verifier.verify(tampered, sig, null) }.reason)
        assertEquals(Reason.BAD_SIGNATURE, assertThrows<FixturesException> { verifier.verify(json, TestSigner().sign(json), null) }.reason)
        assertEquals(Reason.BAD_SIGNATURE, assertThrows<FixturesException> { verifier.verify(json, "!!!".toByteArray(), null) }.reason)
        assertEquals(Reason.BAD_SIGNATURE, assertThrows<FixturesException> { verifier.verify(json, "AAAA".toByteArray(), null) }.reason)
    }

    @Test
    fun `rotation de cle - une des cles suffit`() {
        val newSigner = TestSigner()
        val v = FixturesVerifier(FixturesVerifier.parseKeyList("${signer.publicKeyBase64}, ${newSigner.publicKeyBase64}"), now)
        val json = doc()
        v.verify(json, signer.sign(json), null)
        v.verify(json, newSigner.sign(json), null)
        assertEquals(Reason.BAD_SIGNATURE, assertThrows<FixturesException> { v.verify(json, TestSigner().sign(json), null) }.reason)
    }

    @Test
    fun `fichier expire`() {
        val json = doc(validUntil = "2026-10-20T07:59:59Z", generatedAt = "2026-10-19T06:00:00Z")
        assertEquals(Reason.EXPIRED, assertThrows<FixturesException> { verifier.verify(json, signer.sign(json), null) }.reason)
    }

    @Test
    fun `anti-rollback`() {
        val json = doc(generatedAt = "2026-10-20T03:00:00Z")
        val sig = signer.sign(json)
        assertEquals(
            Reason.ROLLBACK,
            assertThrows<FixturesException> { verifier.verify(json, sig, Instant.parse("2026-10-20T06:00:00Z")) }.reason,
        )
        // Meme generated_at = meme fichier re-telecharge : accepte.
        verifier.verify(json, sig, Instant.parse("2026-10-20T03:00:00Z"))
    }

    @Test
    fun `schema inconnu et JSON signe mais malforme`() {
        val json = doc(schema = 2)
        assertEquals(Reason.UNKNOWN_SCHEMA, assertThrows<FixturesException> { verifier.verify(json, signer.sign(json), null) }.reason)
        val bad = """{"schema":1}""".toByteArray()
        assertEquals(Reason.MALFORMED, assertThrows<FixturesException> { verifier.verify(bad, signer.sign(bad), null) }.reason)
        val badDate = doc(generatedAt = "hier")
        assertEquals(Reason.MALFORMED, assertThrows<FixturesException> { verifier.verify(badDate, signer.sign(badDate), null) }.reason)
    }

    @Test
    fun `cle placeholder - non configure`() {
        val v = FixturesVerifier(FixturesVerifier.parseKeyList(""), now)
        assertFalse(v.isConfigured)
        val json = doc()
        assertEquals(Reason.NO_PUBLIC_KEY, assertThrows<FixturesException> { v.verify(json, signer.sign(json), null) }.reason)
        assertFalse(FixturesVerifier(listOf("pas-du-base64", "AAAA"), now).isConfigured)
    }

    @Test
    fun `fichier trop gros`() {
        val json = ByteArray(FixturesVerifier.MAX_JSON_BYTES + 1)
        assertEquals(Reason.TOO_LARGE, assertThrows<FixturesException> { verifier.verify(json, signer.sign(json), null) }.reason)
    }
}
