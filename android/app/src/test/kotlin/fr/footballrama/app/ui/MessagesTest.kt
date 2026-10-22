package fr.footballrama.app.ui

import fr.footballrama.app.R
import fr.footballrama.app.data.RefreshError
import fr.footballrama.app.data.RefreshState
import fr.footballrama.app.settings.UserAgentRules
import fr.footballrama.app.ui.logic.ServerUrlParser
import fr.footballrama.core.matching.EpgSource
import fr.footballrama.core.xtream.XtreamException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessagesTest {

    @Test
    fun `chaque erreur de rafraichissement a un message distinct`() {
        val ids = RefreshError.entries.map { Messages.refreshError(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(RefreshError.entries.size, ids.toSet().size)
    }

    @Test
    fun `erreurs principales`() {
        assertEquals(R.string.error_auth, Messages.refreshError(RefreshError.AUTH))
        assertEquals(R.string.error_network, Messages.refreshError(RefreshError.NETWORK))
        assertEquals(R.string.error_timeout, Messages.refreshError(RefreshError.TIMEOUT))
        assertEquals(R.string.error_not_configured, Messages.refreshError(RefreshError.NOT_CONFIGURED))
    }

    @Test
    fun `erreurs de connexion Xtream`() {
        XtreamException.Kind.entries.forEach { assertNotEquals(0, Messages.xtreamError(it)) }
        assertEquals(R.string.error_auth, Messages.xtreamError(XtreamException.Kind.AUTH))
        assertEquals(R.string.error_network, Messages.xtreamError(XtreamException.Kind.NETWORK))
        assertEquals(R.string.error_timeout, Messages.xtreamError(XtreamException.Kind.TIMEOUT))
        assertEquals(R.string.error_not_xtream, Messages.xtreamError(XtreamException.Kind.HTTP, 404))
        assertEquals(R.string.error_http, Messages.xtreamError(XtreamException.Kind.HTTP, 400))
    }

    @Test
    fun `adresse, user-agent, etapes`() {
        val urlIds = ServerUrlParser.Reason.entries.map { Messages.urlError(it) }
        assertEquals(urlIds.size, urlIds.toSet().size)
        UserAgentRules.Reason.entries.forEach { assertNotEquals(0, Messages.userAgentError(it)) }
        val steps = RefreshState.Step.entries.map { Messages.step(it) }
        assertEquals(steps.size, steps.toSet().size)
        assertEquals(R.string.step_epg_channels, Messages.step(RefreshState.Step.EPG_CHANNELS))
        val sources = EpgSource.entries.map { Messages.epgSource(it) }
        assertEquals(sources.size, sources.toSet().size)
    }
}
