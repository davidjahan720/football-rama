package fr.footballrama.core

import fr.footballrama.core.fixtures.FixturesVerifier
import fr.footballrama.core.update.UpdateCheckResult
import fr.footballrama.core.update.UpdateManifestException
import fr.footballrama.core.update.UpdateManifestException.Reason
import fr.footballrama.core.update.UpdateManifestVerifier
import fr.footballrama.core.update.UpdatePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UpdateManifestVerifierTest {

    private val signer = TestSigner()
    private val verifier = UpdateManifestVerifier(FixturesVerifier(listOf(signer.publicKeyBase64)))

    private fun manifest(
        versionCode: String = "2",
        versionName: String = "\"1.0.1\"",
        url: String = "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/FootballRama-1.0.1.apk",
        sha256: String = SHA,
        size: String = "12345678",
        minSdk: String = "26",
        schema: String = "1",
        notes: String = "\"Corrections\"",
    ): ByteArray =
        (
            """{"schema":$schema,"version_code":$versionCode,"version_name":$versionName,"apk_url":"$url",""" +
                """"sha256":"$sha256","size":$size,"min_sdk":$minSdk,"notes":$notes}"""
            ).toByteArray()

    private fun check(json: ByteArray, current: Int = 1, sdk: Int = 30) = verifier.check(json, signer.sign(json), current, sdk)

    private fun reason(json: ByteArray, sig: ByteArray = signer.sign(json)) =
        assertThrows<UpdateManifestException> { verifier.check(json, sig, 1, 30) }.reason

    @Test
    fun `manifeste signe valide - mise a jour disponible`() {
        val r = check(manifest())
        assertTrue(r is UpdateCheckResult.Available)
        val release = (r as UpdateCheckResult.Available).release
        assertEquals(2, release.versionCode)
        assertEquals("1.0.1", release.versionName)
        assertEquals(SHA, release.sha256)
        assertEquals(12_345_678L, release.size)
        assertEquals("github.com", release.apkUrl.host)
        assertEquals("Corrections", release.notes)
    }

    @Test
    fun `version egale ou inferieure ignoree (rejeu d un ancien manifeste)`() {
        assertEquals(UpdateCheckResult.UpToDate, check(manifest(versionCode = "2"), current = 2))
        assertEquals(UpdateCheckResult.UpToDate, check(manifest(versionCode = "1"), current = 2))
    }

    @Test
    fun `android trop ancien - incompatible`() {
        assertTrue(check(manifest(minSdk = "31"), sdk = 30) is UpdateCheckResult.Incompatible)
    }

    @Test
    fun `signature invalide - octets modifies, autre cle, signature vide`() {
        val json = manifest()
        val tampered = manifest(sha256 = SHA.replaceRange(0, 1, "b"))
        assertEquals(Reason.BAD_SIGNATURE, reason(tampered, signer.sign(json)))
        assertEquals(Reason.BAD_SIGNATURE, reason(json, TestSigner().sign(json)))
        assertEquals(Reason.BAD_SIGNATURE, reason(json, ByteArray(0)))
        assertEquals(Reason.BAD_SIGNATURE, reason(json, "%%%".toByteArray()))
    }

    @Test
    fun `aucune cle publique dans le build`() {
        val v = UpdateManifestVerifier(FixturesVerifier(listOf("REMPLACER_PAR_LA_CLE")))
        assertFalse(v.isConfigured)
        val json = manifest()
        assertEquals(Reason.NO_PUBLIC_KEY, assertThrows<UpdateManifestException> { v.check(json, signer.sign(json), 1, 30) }.reason)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://github.com/davidjahan720/football-rama/releases/download/v1.0.1/FootballRama-1.0.1.apk",
            "https://github.com/autre/football-rama/releases/download/v1.0.1/FootballRama-1.0.1.apk",
            "https://github.com/davidjahan720/football-rama-fork/releases/download/v1.0.1/a.apk",
            "https://github.com.evil.example/davidjahan720/football-rama/releases/download/v1.0.1/a.apk",
            "https://evil.example/davidjahan720/football-rama/releases/download/v1.0.1/a.apk",
            "https://github.com:8443/davidjahan720/football-rama/releases/download/v1.0.1/a.apk",
            "https://user@github.com/davidjahan720/football-rama/releases/download/v1.0.1/a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/a.apk?x=1",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/a.apk#f",
            "https://github.com/davidjahan720/football-rama/releases/download/../../../evil/a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/../a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/%2e%2e/a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/a.zip",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/sub/a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download//a.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/a b.apk",
            "https://github.com/davidjahan720/football-rama/releases/download/v1.0.1/a.apk/",
            "https://github.com/davidjahan720/football-rama/releases/download/\\\\evil/a.apk",
            "https://GITHUB.com/davidjahan720/football-rama/releases/download/v1.0.1/a.apk",
        ],
    )
    fun `URL hors du prefixe strict refusee`(url: String) {
        assertFalse(UpdatePolicy.isAllowedApkUrl(url), url)
        assertEquals(Reason.BAD_URL, reason(manifest(url = url)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "abc",
            // 63 et 65 caracteres
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            // majuscules et caractere non hexadecimal
            "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        ],
    )
    fun `sha256 malforme refuse`(sha: String) {
        assertEquals(Reason.BAD_SHA256, reason(manifest(sha256 = sha)))
    }

    @Test
    fun `taille max et taille nulle refusees`() {
        assertEquals(Reason.BAD_SIZE, reason(manifest(size = (UpdatePolicy.MAX_APK_BYTES + 1).toString())))
        assertEquals(Reason.BAD_SIZE, reason(manifest(size = "0")))
        assertEquals(Reason.BAD_SIZE, reason(manifest(size = "-5")))
        // Exactement la limite : accepte.
        assertTrue(check(manifest(size = UpdatePolicy.MAX_APK_BYTES.toString())) is UpdateCheckResult.Available)
    }

    @Test
    fun `manifeste trop gros refuse avant toute verification`() {
        val big = manifest(notes = "\"" + "x".repeat(UpdatePolicy.MAX_MANIFEST_BYTES) + "\"")
        assertEquals(Reason.TOO_LARGE, reason(big))
    }

    @Test
    fun `versions, schema et champs invalides`() {
        assertEquals(Reason.BAD_VERSION, reason(manifest(versionCode = "0")))
        assertEquals(Reason.BAD_VERSION, reason(manifest(versionCode = "-1")))
        assertEquals(Reason.BAD_VERSION, reason(manifest(versionCode = "3000000000")))
        assertEquals(Reason.BAD_VERSION, reason(manifest(versionName = "\"1.0.1 <script>\"")))
        assertEquals(Reason.UNKNOWN_SCHEMA, reason(manifest(schema = "2")))
        assertEquals(Reason.MALFORMED, reason(manifest(versionCode = "\"abc\"")))
        assertEquals(Reason.MALFORMED, reason(manifest(notes = "\"" + "n".repeat(UpdatePolicy.MAX_NOTES + 1) + "\"")))
        assertEquals(Reason.MALFORMED, reason("""{"schema":1}""".toByteArray()))
        assertEquals(Reason.MALFORMED, reason("pas du json".toByteArray()))
    }

    @Test
    fun `un fixtures json signe n est pas accepte comme manifeste`() {
        val fixtures = """{"schema":1,"generated_at":"2026-10-20T06:00:00Z","valid_until":"2026-10-21T18:00:00Z",
            "window":{"from":"2026-10-20T00:00:00Z","to":"2026-10-22T03:00:00Z"},"matches":[]}""".toByteArray()
        assertEquals(Reason.MALFORMED, reason(fixtures))
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
