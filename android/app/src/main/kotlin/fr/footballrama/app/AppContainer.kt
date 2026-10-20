package fr.footballrama.app

import android.content.Context
import android.util.Xml
import fr.footballrama.app.data.CredentialStore
import fr.footballrama.app.data.MatchRepository
import fr.footballrama.app.data.RefreshScheduler
import fr.footballrama.app.settings.UiPreferences
import fr.footballrama.core.epg.XmltvParser
import fr.footballrama.core.fixtures.FixturesClient
import fr.footballrama.core.fixtures.FixturesVerifier
import fr.footballrama.core.matching.MatchPipeline
import fr.footballrama.core.xtream.XtreamAccount
import fr.footballrama.core.xtream.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

/** Graphe d'objets de l'application (sans framework d'injection). Une seule instance, dans [FootballRamaApp]. */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Client HTTP partage. AUCUN intercepteur de log (les URL Xtream contiennent les identifiants). */
    private val okHttp = OkHttpClient()

    val credentialStore = CredentialStore(context)

    /** Reglages non sensibles (User-Agent avance, filtre pays). */
    val uiPreferences = UiPreferences(context)

    /**
     * Client Xtream partage par l'UI (verification a la connexion) et le depot. Le User-Agent personnalise
     * (reglage avance, ASCII imprimable valide par [fr.footballrama.app.settings.UserAgentRules]) est applique
     * a chaque requete par un intercepteur : un changement de reglage prend effet sans recreer le client.
     * Intercepteur limite au trafic Xtream (le telechargement du calendrier garde l'User-Agent par defaut).
     */
    val xtreamClient: XtreamClient = XtreamClient(
        okHttp.newBuilder().addInterceptor { chain ->
            val ua = uiPreferences.userAgent
            val request = if (ua != null) chain.request().newBuilder().header("User-Agent", ua).build() else chain.request()
            chain.proceed(request)
        }.build(),
    )

    val matchRepository: MatchRepository by lazy {
        MatchRepository(
            credentialStore = credentialStore,
            xtreamClient = xtreamClient,
            fixturesClient = FixturesClient(
                baseUrl = BuildConfig.FIXTURES_BASE_URL,
                baseClient = okHttp,
                httpCacheDir = File(context.cacheDir, "http-fixtures"),
            ),
            verifier = FixturesVerifier(FixturesVerifier.parseKeyList(BuildConfig.FIXTURES_PUBLIC_KEYS)),
            pipeline = MatchPipeline(XmltvParser { Xml.newPullParser() }),
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            scope = appScope,
        )
    }

    /** Enregistre les identifiants puis planifie les rafraichissements. Voir [CredentialStore.save]. */
    suspend fun signIn(serverUrl: String, username: String, password: String): XtreamAccount {
        val account = credentialStore.save(serverUrl, username, password)
        RefreshScheduler.schedulePeriodic(context)
        RefreshScheduler.refreshNow(context)
        return account
    }

    /** Se deconnecter : annule les taches, efface identifiants et resultats. */
    suspend fun signOut() {
        RefreshScheduler.cancelAll(context)
        matchRepository.signOut()
        uiPreferences.clear()
    }
}
