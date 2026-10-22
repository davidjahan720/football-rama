package fr.footballrama.core.update

import fr.footballrama.core.fixtures.FixturesVerifier
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
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateDownloadException(val kind: Kind, val httpCode: Int = 0) : Exception("update download failed: $kind") {
    enum class Kind { NETWORK, HTTP, TOO_LARGE, NOT_HTTPS, SIZE_MISMATCH, HASH_MISMATCH, STORAGE }
}

/** Octets bruts de update.json + update.json.sig (a verifier avec [UpdateManifestVerifier]). */
class SignedManifest(val json: ByteArray, val signature: ByteArray)

/**
 * Telecharge update.json et update.json.sig depuis le meme hebergement que le calendrier (FIXTURES_BASE_URL).
 * HTTPS impose dans le code, taille max 64 Ko, requetes conditionnelles (ETag / 304) via le cache OkHttp.
 * Un 404 (aucune release publiee) est remonte comme HTTP 404 : l'appelant le traite comme « a jour ».
 */
class UpdateManifestClient(
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
            if (httpCacheDir != null) cache(Cache(httpCacheDir, 256L * 1024))
        }
        .build()

    val isConfigured: Boolean get() = base != null

    override fun close() {
        client.cache?.close()
    }

    /** @throws UpdateDownloadException */
    fun download(): SignedManifest {
        val b = base ?: throw UpdateDownloadException(UpdateDownloadException.Kind.NOT_HTTPS)
        val json = get(b.resolve(MANIFEST_NAME)!!, UpdatePolicy.MAX_MANIFEST_BYTES.toLong())
        val sig = get(b.resolve(SIGNATURE_NAME)!!, FixturesVerifier.MAX_SIG_BYTES.toLong())
        return SignedManifest(json, sig)
    }

    private fun get(url: HttpUrl, max: Long): ByteArray {
        val request = Request.Builder().url(url).cacheControl(REVALIDATE).get().build()
        try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw UpdateDownloadException(UpdateDownloadException.Kind.HTTP, r.code)
                return LimitedInputStream(r.body.byteStream(), max).readBytes()
            }
        } catch (e: UpdateDownloadException) {
            throw e
        } catch (_: SizeLimitExceededException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.TOO_LARGE)
        } catch (_: IOException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.NETWORK)
        } catch (_: RuntimeException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.NETWORK)
        }
    }

    companion object {
        const val MANIFEST_NAME = "update.json"
        const val SIGNATURE_NAME = "update.json.sig"
        private val REVALIDATE: CacheControl = CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build()
    }
}

/**
 * Telecharge l'APK d'une [AppRelease] verifiee vers un fichier, en verifiant taille et SHA-256 pendant l'ecriture.
 * - Redirections suivies MANUELLEMENT (5 au plus) : chaque saut est valide AVANT toute connexion, https obligatoire
 *   et hote autorise ([allowedHost] : github.com -> *.githubusercontent.com). Tout le reste est refuse.
 * - Taille bornee par la taille annoncee dans le manifeste signe (et par [UpdatePolicy.MAX_APK_BYTES]).
 * - En cas d'echec, le fichier partiel est supprime : jamais d'APK non verifie sur le disque.
 */
class ApkDownloader(
    baseClient: OkHttpClient = OkHttpClient(),
    private val allowCleartextForTests: Boolean = false,
    private val allowedHost: (String) -> Boolean = ::isGitHubReleaseHost,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .apply {
            if (!allowCleartextForTests) connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.RESTRICTED_TLS))
        }
        .build()

    private fun isAllowed(url: HttpUrl): Boolean =
        (url.isHttps || allowCleartextForTests) && allowedHost(url.host) && url.username.isEmpty() && url.password.isEmpty()

    /**
     * @param onProgress octets recus (sur [AppRelease.size]).
     * @throws UpdateDownloadException ([dest] supprime)
     */
    fun download(release: AppRelease, dest: File, onProgress: (Long) -> Unit = {}) {
        val expected = release.size
        if (expected <= 0 || expected > UpdatePolicy.MAX_APK_BYTES) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.TOO_LARGE)
        }
        var ok = false
        try {
            var url = release.apkUrl
            var redirects = 0
            while (true) {
                if (!isAllowed(url)) throw UpdateDownloadException(UpdateDownloadException.Kind.NOT_HTTPS)
                val response = client.newCall(Request.Builder().url(url).get().build()).execute()
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { url.resolve(it) }
                    response.close()
                    if (next == null || ++redirects > MAX_REDIRECTS) {
                        throw UpdateDownloadException(UpdateDownloadException.Kind.HTTP, response.code)
                    }
                    url = next
                    continue
                }
                response.use { r ->
                    if (!r.isSuccessful) throw UpdateDownloadException(UpdateDownloadException.Kind.HTTP, r.code)
                    save(r.body.byteStream(), r.body.contentLength(), release, dest, onProgress)
                }
                break
            }
            ok = true
        } catch (e: UpdateDownloadException) {
            throw e
        } catch (_: IOException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.NETWORK)
        } catch (_: RuntimeException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.NETWORK)
        } finally {
            if (!ok) dest.delete()
        }
    }

    private fun save(body: InputStream, declared: Long, release: AppRelease, dest: File, onProgress: (Long) -> Unit) {
        val expected = release.size
        if (declared > expected) throw UpdateDownloadException(UpdateDownloadException.Kind.TOO_LARGE)
        if (declared >= 0 && declared != expected) throw UpdateDownloadException(UpdateDownloadException.Kind.SIZE_MISMATCH)
        val digest = MessageDigest.getInstance("SHA-256")
        val input = LimitedInputStream(body, expected, onProgress)
        try {
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
        } catch (_: SizeLimitExceededException) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.TOO_LARGE)
        } catch (_: IOException) {
            // Coupure reseau en cours de lecture ou erreur d'ecriture locale (espace disque).
            val lowSpace = dest.absoluteFile.parentFile?.usableSpace?.let { it < expected } == true
            throw UpdateDownloadException(if (lowSpace) UpdateDownloadException.Kind.STORAGE else UpdateDownloadException.Kind.NETWORK)
        }
        if (input.count != expected) throw UpdateDownloadException(UpdateDownloadException.Kind.SIZE_MISMATCH)
        if (!FileIntegrity.digestMatches(digest.digest(), release.sha256)) {
            throw UpdateDownloadException(UpdateDownloadException.Kind.HASH_MISMATCH)
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5

        /** github.com (URL de la release) et ses CDN de telechargement (*.githubusercontent.com). */
        fun isGitHubReleaseHost(host: String): Boolean =
            host == "github.com" || host.endsWith(".githubusercontent.com")
    }
}

/** SHA-256 et verification d'integrite d'un fichier (avant installation). */
object FileIntegrity {
    /** Vrai si [file] fait exactement [expectedSize] octets et a le SHA-256 [expectedSha256Hex] (64 hex). */
    fun matches(file: File, expectedSize: Long, expectedSha256Hex: String): Boolean {
        if (!UpdatePolicy.isSha256Hex(expectedSha256Hex) || !file.isFile || file.length() != expectedSize) return false
        return MessageDigest.isEqual(sha256(file), hexToBytes(expectedSha256Hex))
    }

    /** Comparaison en temps constant d'un condensat SHA-256 deja calcule avec la valeur signee. */
    fun digestMatches(digest: ByteArray, expectedSha256Hex: String): Boolean =
        UpdatePolicy.isSha256Hex(expectedSha256Hex) && MessageDigest.isEqual(digest, hexToBytes(expectedSha256Hex))

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
