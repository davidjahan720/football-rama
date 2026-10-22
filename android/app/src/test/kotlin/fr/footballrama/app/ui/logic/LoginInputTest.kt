package fr.footballrama.app.ui.logic

import fr.footballrama.app.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoginInputTest {

    @Test
    fun `champs separes`() {
        val r = LoginInput.prepare("iptv.example:8080", "alice", "pw") as LoginInput.Result.Ready
        assertEquals("http://iptv.example:8080", r.serverUrl)
        assertEquals("alice", r.username)
        assertEquals("pw", r.password)
        assertFalse(r.toString().contains("pw"))
    }

    @Test
    fun `lien M3U complet - identifiants extraits si champs vides, sinon champs prioritaires`() {
        val m3u = "http://iptv.example:8080/get.php?username=bob&password=secret&type=m3u_plus&output=ts"
        val r = LoginInput.prepare(m3u, "", "") as LoginInput.Result.Ready
        assertEquals("http://iptv.example:8080", r.serverUrl)
        assertEquals("bob", r.username)
        assertEquals("secret", r.password)
        val r2 = LoginInput.prepare(m3u, "carol", "pw2") as LoginInput.Result.Ready
        assertEquals("carol", r2.username)
        assertEquals("pw2", r2.password)
    }

    @Test
    fun `erreurs`() {
        assertEquals(LoginInput.Result.Invalid(R.string.url_error_empty), LoginInput.prepare("", "a", "b"))
        assertEquals(LoginInput.Result.Invalid(R.string.url_error_scheme), LoginInput.prepare("rtmp://x", "a", "b"))
        assertEquals(LoginInput.Result.Invalid(R.string.login_error_username), LoginInput.prepare("h:80", " ", "b"))
        assertEquals(LoginInput.Result.Invalid(R.string.login_error_password), LoginInput.prepare("h:80", "a", ""))
        assertTrue(LoginInput.prepare("h:80", "a".repeat(300), "b") is LoginInput.Result.Invalid)
    }
}
