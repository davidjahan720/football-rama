package fr.footballrama.core.fixtures

import fr.footballrama.core.io.LimitedInputStream
import fr.footballrama.core.io.SizeLimitExceededException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Octets bruts telecharges (a verifier avec [FixturesVerifier]). */
class SignedFixtures(val json: ByteArray, val signature: ByteArray)

class FixturesDownloadException(val kind: Kind, val httpCode: Int = 0) : Exception("fixtures download failed: $kind") {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, NOT_HTTPS }
}

/**
 * Telecharge fixtures.json et fixtures.json.sig depuis l'hebergement statique.
 * - HTTPS impose dans le code (ConnectionSpec sans CLEARTEXT), en plus de network_security_config.
 * - Requetes conditionnelles : cache HTTP OkHttp ([httpCacheDir]) -> If-None-Match / 304, pour limiter la
 *   bande passante (10 000 boitiers). Le cache n'est pas une surface d'attaque : tout est verifie par signature.
 */
class FixturesClient(
    baseUrl: String,
    baseClient: OkHttpClient = OkHttpClient(),
    httpCacheDir: File? = null,
    allowCleartextForTests: Boolean = false,
) : java.io.Closeable {
    private val base: HttpUrl? = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull()
        ?.takeIf { it.isHttps || allowCleartextForTests }

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .apply {
            if (!allowCleartextForTests) connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.RESTRICTED_TLS))
            if (httpCacheDir != null) cache(Cache(httpCacheDir, 4L * 1024 * 1024))
        }
        .build()

    val isConfigured: Boolean get() = base != null

    /** Ferme le cache HTTP (fichiers journal). */
    override fun close() {
        client.cache?.close()
    }

    fun download(): SignedFixtures {
        val b = base ?: throw FixturesDownloadException(FixturesDownloadException.Kind.NOT_HTTPS)
        val json = get(b.resolve("fixtures.json")!!, FixturesVerifier.MAX_JSON_BYTES.toLong())
        val sig = get(b.resolve("fixtures.json.sig")!!, FixturesVerifier.MAX_SIG_BYTES.toLong())
        return SignedFixtures(json, sig)
    }

    private fun get(url: HttpUrl, max: Long): ByteArray {
        // max-age=0 : toujours revalider aupres du serveur (If-None-Match -> 304 si inchange, corps servi
        // depuis le cache local). NB : "no-cache" cote requete ferait sauter le cache sans requete conditionnelle.
        val request = Request.Builder().url(url).cacheControl(REVALIDATE).get().build()
        try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw FixturesDownloadException(FixturesDownloadException.Kind.HTTP, r.code)
                return LimitedInputStream(r.body.byteStream(), max).readBytes()
            }
        } catch (e: FixturesDownloadException) {
            throw e
        } catch (e: SizeLimitExceededException) {
            throw FixturesDownloadException(FixturesDownloadException.Kind.TOO_LARGE)
        } catch (e: IOException) {
            throw FixturesDownloadException(FixturesDownloadException.Kind.NETWORK)
        } catch (e: RuntimeException) {
            throw FixturesDownloadException(FixturesDownloadException.Kind.NETWORK)
        }
    }

    private companion object {
        val REVALIDATE: CacheControl = CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build()
    }
}
