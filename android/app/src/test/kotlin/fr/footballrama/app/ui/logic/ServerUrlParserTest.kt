package fr.footballrama.app.ui.logic

import fr.footballrama.app.ui.logic.ServerUrlParser.Reason
import fr.footballrama.app.ui.logic.ServerUrlParser.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerUrlParserTest {

    private fun valid(input: String) = ServerUrlParser.parse(input) as Result.Valid
    private fun reason(input: String) = (ServerUrlParser.parse(input) as Result.Invalid).reason

    @Test
    fun `adresses simples normalisees`() {
        val cases = mapOf(
            "exemple.com:8080" to "http://exemple.com:8080",
            "  http://exemple.com:8080/  " to "http://exemple.com:8080",
            "HTTP://Exemple.COM:8080" to "http://exemple.com:8080",
            "https://exemple.com" to "https://exemple.com",
            "https://exemple.com:443" to "https://exemple.com",
            "http://exemple.com:25461/base/" to "http://exemple.com:25461/base",
            "http://192.168.1.10:8000" to "http://192.168.1.10:8000",
        )
        for ((input, expected) in cases) {
            val r = valid(input)
            assertEquals(expected, r.serverUrl, input)
            assertNull(r.extractedUsername)
            assertNull(r.extractedPassword)
        }
    }

    @Test
    fun `http signale comme non chiffre, https non`() {
        assertTrue(valid("exemple.com:8080").isCleartext)
        assertTrue(valid("http://exemple.com").isCleartext)
        assertFalse(valid("https://exemple.com").isCleartext)
    }

    @Test
    fun `url player_api complete collee - identifiants extraits et chemin retire`() {
        val r = valid("http://exemple.com:8080/player_api.php?username=jean&password=s3cr%26t")
        assertEquals("http://exemple.com:8080", r.serverUrl)
        assertEquals("jean", r.extractedUsername)
        assertEquals("s3cr&t", r.extractedPassword)
    }

    @Test
    fun `lien m3u get_php colle`() {
        val r = valid("http://exemple.com:8080/get.php?username=jean&password=pw&type=m3u_plus&output=ts")
        assertEquals("http://exemple.com:8080", r.serverUrl)
        assertEquals("jean", r.extractedUsername)
        assertEquals("pw", r.extractedPassword)
    }

    @Test
    fun `xmltv et player_api sans parametres - chemin retire`() {
        assertEquals("http://exemple.com:8080", valid("http://exemple.com:8080/xmltv.php").serverUrl)
        assertEquals("https://exemple.com/sub", valid("https://exemple.com/sub/player_api.php").serverUrl)
    }

    @Test
    fun `identifiants dans user-info extraits et retires`() {
        val r = valid("http://jean:pw@exemple.com:8080")
        assertEquals("http://exemple.com:8080", r.serverUrl)
        assertEquals("jean", r.extractedUsername)
        assertEquals("pw", r.extractedPassword)
    }

    @Test
    fun `toString ne revele pas les identifiants`() {
        val r = valid("http://exemple.com/player_api.php?username=jean&password=motdepasse")
        assertFalse(r.toString().contains("motdepasse"))
        assertFalse(r.toString().contains("jean"))
    }

    @Test
    fun `fragment ignore`() {
        assertEquals("http://exemple.com:8080", valid("http://exemple.com:8080/#x").serverUrl)
    }

    @Test
    fun `refus - vide`() {
        assertEquals(Reason.EMPTY, reason(""))
        assertEquals(Reason.EMPTY, reason("   "))
    }

    @Test
    fun `refus - schema non supporte`() {
        for (input in listOf("ftp://exemple.com", "rtmp://exemple.com/live", "file:///etc/passwd", "javascript://x", "mailto:a@b.c")) {
            assertEquals(Reason.BAD_SCHEME, reason(input), input)
        }
    }

    @Test
    fun `refus - adresse illisible`() {
        for (input in listOf("http://exemple.com:99999", "http://exe mple.com", "http://", "http://exemple.com:port", "http://exemple.com\n/x")) {
            val r = reason(input)
            assertTrue(r == Reason.MALFORMED || r == Reason.NO_HOST, "$input -> $r")
        }
    }

    @Test
    fun `refus - identifiants partiels`() {
        assertEquals(Reason.PARTIAL_CREDENTIALS, reason("http://exemple.com/player_api.php?username=jean"))
        assertEquals(Reason.PARTIAL_CREDENTIALS, reason("http://exemple.com/player_api.php?password=x"))
        assertEquals(Reason.PARTIAL_CREDENTIALS, reason("http://jean@exemple.com"))
    }

    @Test
    fun `refus - parametres inconnus`() {
        assertEquals(Reason.UNKNOWN_PARAMETERS, reason("http://exemple.com/?redirect=x"))
        assertEquals(Reason.UNKNOWN_PARAMETERS, reason("http://exemple.com/player_api.php?username=a&password=b&token=c"))
    }

    @Test
    fun `refus - lien de flux colle`() {
        assertEquals(Reason.STREAM_URL, reason("http://exemple.com:8080/live/jean/pw/12345.ts"))
        assertEquals(Reason.STREAM_URL, reason("http://exemple.com:8080/playlist.m3u8"))
    }

    @Test
    fun `adresse trop longue refusee`() {
        assertEquals(Reason.MALFORMED, reason("http://exemple.com/" + "a".repeat(3000)))
    }
}
