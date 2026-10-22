package fr.footballrama.app.ui

import androidx.annotation.StringRes
import fr.footballrama.app.R
import fr.footballrama.app.data.RefreshError
import fr.footballrama.app.data.RefreshState
import fr.footballrama.app.settings.UserAgentRules
import fr.footballrama.app.ui.logic.ServerUrlParser
import fr.footballrama.core.matching.EpgSource
import fr.footballrama.core.xtream.XtreamException

/** Correspondance erreurs -> messages francais (strings.xml). Aucun message technique ni identifiant affiche. */
object Messages {

    @StringRes
    fun refreshError(error: RefreshError): Int = when (error) {
        RefreshError.NOT_CONFIGURED -> R.string.error_not_configured
        RefreshError.NO_ACCOUNT -> R.string.error_no_account
        RefreshError.NETWORK -> R.string.error_network
        RefreshError.TIMEOUT -> R.string.error_timeout
        RefreshError.TLS -> R.string.error_tls
        RefreshError.AUTH -> R.string.error_auth
        RefreshError.SERVER -> R.string.error_server
        RefreshError.INVALID_RESPONSE -> R.string.error_invalid_response
        RefreshError.TOO_LARGE -> R.string.error_too_large
        RefreshError.EPG_INVALID -> R.string.error_epg_invalid
        RefreshError.EPG_EMPTY -> R.string.error_epg_empty
        RefreshError.RATE_LIMITED -> R.string.error_rate_limited
        RefreshError.FIXTURES_UNAVAILABLE -> R.string.error_fixtures_unavailable
        RefreshError.FIXTURES_EXPIRED -> R.string.error_fixtures_expired
        RefreshError.STORAGE -> R.string.error_storage
        RefreshError.UNKNOWN -> R.string.error_unknown
    }

    /** Erreurs de la verification des identifiants a la connexion. */
    @StringRes
    fun xtreamError(kind: XtreamException.Kind, httpCode: Int = 0): Int = when (kind) {
        XtreamException.Kind.NETWORK -> R.string.error_network
        XtreamException.Kind.TIMEOUT -> R.string.error_timeout
        XtreamException.Kind.TLS -> R.string.error_tls
        XtreamException.Kind.AUTH -> R.string.error_auth
        XtreamException.Kind.HTTP -> if (httpCode == 404) R.string.error_not_xtream else R.string.error_http
        XtreamException.Kind.SERVER -> R.string.error_server
        XtreamException.Kind.TOO_LARGE -> R.string.error_too_large
        XtreamException.Kind.INVALID_RESPONSE -> R.string.error_not_xtream
        XtreamException.Kind.CANCELLED -> R.string.error_cancelled
        XtreamException.Kind.STORAGE -> R.string.error_storage
    }

    @StringRes
    fun urlError(reason: ServerUrlParser.Reason): Int = when (reason) {
        ServerUrlParser.Reason.EMPTY -> R.string.url_error_empty
        ServerUrlParser.Reason.BAD_SCHEME -> R.string.url_error_scheme
        ServerUrlParser.Reason.MALFORMED -> R.string.url_error_malformed
        ServerUrlParser.Reason.NO_HOST -> R.string.url_error_no_host
        ServerUrlParser.Reason.PARTIAL_CREDENTIALS -> R.string.url_error_partial_credentials
        ServerUrlParser.Reason.UNKNOWN_PARAMETERS -> R.string.url_error_unknown_parameters
        ServerUrlParser.Reason.STREAM_URL -> R.string.url_error_stream
    }

    @StringRes
    fun userAgentError(reason: UserAgentRules.Reason): Int = when (reason) {
        UserAgentRules.Reason.TOO_LONG -> R.string.ua_error_too_long
        UserAgentRules.Reason.INVALID_CHARACTERS -> R.string.ua_error_characters
    }

    @StringRes
    fun step(step: RefreshState.Step): Int = when (step) {
        RefreshState.Step.FIXTURES -> R.string.step_fixtures
        RefreshState.Step.CHANNELS -> R.string.step_channels
        RefreshState.Step.EPG_DOWNLOAD -> R.string.step_epg_download
        RefreshState.Step.EPG_PARSE -> R.string.step_epg_parse
        RefreshState.Step.EPG_CHANNELS -> R.string.step_epg_channels
        RefreshState.Step.MATCHING -> R.string.step_matching
    }

    @StringRes
    fun epgSource(source: EpgSource): Int = when (source) {
        EpgSource.XMLTV -> R.string.epg_source_xmltv
        EpgSource.CHANNELS -> R.string.epg_source_channels
    }
}
