package fr.footballrama.app.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserAgentRulesTest {

    @Test
    fun `vide ou espaces = valeur par defaut`() {
        assertEquals(UserAgentRules.Result.Default, UserAgentRules.validate(""))
        assertEquals(UserAgentRules.Result.Default, UserAgentRules.validate("   "))
    }

    @Test
    fun `valeur ASCII imprimable acceptee et rognee`() {
        assertEquals(UserAgentRules.Result.Custom("IPTVSmartersPro"), UserAgentRules.validate("  IPTVSmartersPro "))
        val ua = "Mozilla/5.0 (Linux; Android 11) VLC/3.0.20 LibVLC/3.0.20"
        assertEquals(UserAgentRules.Result.Custom(ua), UserAgentRules.validate(ua))
    }

    @Test
    fun `longueur maximale 200`() {
        assertEquals(UserAgentRules.Result.Custom("a".repeat(200)), UserAgentRules.validate("a".repeat(200)))
        assertEquals(UserAgentRules.Result.Invalid(UserAgentRules.Reason.TOO_LONG), UserAgentRules.validate("a".repeat(201)))
    }

    @Test
    fun `injection d'en-tete et caracteres non ASCII refuses`() {
        val bad = UserAgentRules.Result.Invalid(UserAgentRules.Reason.INVALID_CHARACTERS)
        assertEquals(bad, UserAgentRules.validate("UA\r\nX-Injected: 1"))
        assertEquals(bad, UserAgentRules.validate("UA\nX"))
        assertEquals(bad, UserAgentRules.validate("Lecteur télé"))
        assertEquals(bad, UserAgentRules.validate("UA\tTab"))
        assertEquals(bad, UserAgentRules.validate("UA\u0000x"))
        assertEquals(bad, UserAgentRules.validate("UA\u007Fx"))
    }
}
