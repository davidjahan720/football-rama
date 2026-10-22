package fr.footballrama.app.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Reglages non sensibles (User-Agent avance, filtres pays et competition). SharedPreferences privees, exclues des
 * sauvegardes (allowBackup=false + dataExtractionRules). Le User-Agent est revalide a la lecture.
 */
class UiPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** User-Agent personnalise, null = valeur par defaut du client HTTP. Lu par l'intercepteur Xtream. */
    @Volatile
    var userAgent: String? = (UserAgentRules.validate(prefs.getString(KEY_UA, null).orEmpty()) as? UserAgentRules.Result.Custom)?.value
        private set

    /** @return false si la valeur est invalide (rien n'est enregistre). Chaine vide = valeur par defaut. */
    fun setUserAgent(input: String): Boolean {
        return when (val r = UserAgentRules.validate(input)) {
            is UserAgentRules.Result.Invalid -> false
            UserAgentRules.Result.Default -> {
                prefs.edit { remove(KEY_UA) }
                userAgent = null
                true
            }
            is UserAgentRules.Result.Custom -> {
                prefs.edit { putString(KEY_UA, r.value) }
                userAgent = r.value
                true
            }
        }
    }

    /** Filtre pays memorise (cle de [fr.footballrama.app.ui.logic.Countries.key]), null = tous. */
    var countryFilter: String?
        get() = prefs.getString(KEY_COUNTRY, null)?.takeIf { it.length <= 100 }
        set(value) {
            prefs.edit { if (value == null) remove(KEY_COUNTRY) else putString(KEY_COUNTRY, value.take(100)) }
        }

    /** Filtre competition memorise (code football-data, ex. "BL1"), null = toutes. */
    var competitionFilter: String?
        get() = prefs.getString(KEY_COMPETITION, null)?.takeIf { it.length <= 100 }
        set(value) {
            prefs.edit { if (value == null) remove(KEY_COMPETITION) else putString(KEY_COMPETITION, value.take(100)) }
        }

    /** "Se deconnecter" : efface aussi les reglages. */
    fun clear() {
        prefs.edit { clear() }
        userAgent = null
    }

    private companion object {
        const val FILE = "ui_settings"
        const val KEY_UA = "user_agent"
        const val KEY_COUNTRY = "country_filter"
        const val KEY_COMPETITION = "competition_filter"
    }
}

/** Regles du User-Agent personnalise : ASCII imprimable (0x20-0x7E), 200 caracteres max. */
object UserAgentRules {
    const val MAX_LENGTH = 200

    sealed interface Result {
        data object Default : Result
        data class Custom(val value: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { TOO_LONG, INVALID_CHARACTERS }

    fun validate(input: String): Result {
        val v = input.trim()
        return when {
            v.isEmpty() -> Result.Default
            v.length > MAX_LENGTH -> Result.Invalid(Reason.TOO_LONG)
            // Exclut CR/LF (injection d'en-tete), tabulations, non-ASCII et caracteres de controle.
            v.any { it.code !in 0x20..0x7E } -> Result.Invalid(Reason.INVALID_CHARACTERS)
            else -> Result.Custom(v)
        }
    }
}
