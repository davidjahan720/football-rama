package fr.footballrama.core.update

import fr.footballrama.core.fixtures.FixturesException
import fr.footballrama.core.fixtures.FixturesVerifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Format brut de update.json, schema 1 (docs/CONTRAT_FIXTURES.md, section « update.json »). */
@Serializable
data class AppReleaseDocument(
    val schema: Int,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    val sha256: String,
    val size: Long,
    @SerialName("min_sdk") val minSdk: Int,
    val notes: String = "",
)

/** Release verifiee (signature + regles strictes). [sha256] en hexadecimal minuscule. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: HttpUrl,
    val sha256: String,
    val size: Long,
    val minSdk: Int,
    val notes: String,
)

class UpdateManifestException(val reason: Reason) : Exception("update manifest rejected: $reason") {
    enum class Reason {
        /** Aucune cle publique exploitable dans le build. */
        NO_PUBLIC_KEY,
        /** Signature invalide (ou incoherence passagere du CDN entre update.json et .sig). */
        BAD_SIGNATURE,
        TOO_LARGE,
        MALFORMED,
        UNKNOWN_SCHEMA,
        /** apk_url hors du prefixe strict des releases GitHub du projet. */
        BAD_URL,
        BAD_SHA256,
        BAD_SIZE,
        BAD_VERSION,
    }
}

sealed interface UpdateCheckResult {
    /** La version installee est la plus recente (ou update.json plus ancien : rejeu ignore). */
    data object UpToDate : UpdateCheckResult

    /** Version plus recente, mais exige un Android plus recent que celui de l'appareil. */
    data class Incompatible(val release: AppRelease) : UpdateCheckResult

    data class Available(val release: AppRelease) : UpdateCheckResult
}

/** Regles de securite des mises a jour, partagees par la verification du manifeste et le telechargement. */
object UpdatePolicy {
    /** Seul prefixe autorise pour apk_url (identique cote publisher). */
    const val APK_URL_PREFIX = "https://github.com/davidjahan720/football-rama/releases/download/"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val MAX_NOTES = 500

    private val SEGMENT = Regex("[A-Za-z0-9._-]{1,100}")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")
    private val VERSION_NAME = Regex("[0-9A-Za-z._-]{1,32}")

    /**
     * Prefixe strict : https://github.com/davidjahan720/football-rama/releases/download/<tag>/<fichier>.apk
     * Chaine brute controlee caractere par caractere AVANT parsing (pas d'encodage %, pas d'identifiants
     * user@host, pas de requete ni de fragment, pas de segment « . » / « .. »), puis reverifiee apres parsing.
     */
    fun isAllowedApkUrl(raw: String): Boolean {
        if (!raw.startsWith(APK_URL_PREFIX)) return false
        val rest = raw.substring(APK_URL_PREFIX.length).split('/')
        if (rest.size != 2) return false
        val (tag, file) = rest
        if (!SEGMENT.matches(tag) || !SEGMENT.matches(file)) return false
        if (tag.all { it == '.' } || !file.endsWith(".apk") || file.length <= ".apk".length) return false
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.query == null && url.fragment == null && url.toString() == raw
    }

    fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)

    fun isValidVersionName(value: String): Boolean = VERSION_NAME.matches(value)
}

/**
 * Verifie update.json + update.json.sig : signature Ed25519 sur les octets exacts (meme cle que le calendrier,
 * via [FixturesVerifier.verifySignature]), puis schema et regles strictes. Rien n'est parse avant la signature.
 */
class UpdateManifestVerifier(private val signatureVerifier: FixturesVerifier) {

    val isConfigured: Boolean get() = signatureVerifier.isConfigured

    /** @throws UpdateManifestException */
    fun verify(json: ByteArray, signature: ByteArray): AppRelease {
        try {
            signatureVerifier.verifySignature(json, signature, UpdatePolicy.MAX_MANIFEST_BYTES)
        } catch (e: FixturesException) {
            throw UpdateManifestException(
                when (e.reason) {
                    FixturesException.Reason.NO_PUBLIC_KEY -> UpdateManifestException.Reason.NO_PUBLIC_KEY
                    FixturesException.Reason.TOO_LARGE -> UpdateManifestException.Reason.TOO_LARGE
                    else -> UpdateManifestException.Reason.BAD_SIGNATURE
                },
            )
        }
        val doc = try {
            JSON.decodeFromString(AppReleaseDocument.serializer(), json.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw UpdateManifestException(UpdateManifestException.Reason.MALFORMED)
        } catch (_: IllegalArgumentException) {
            throw UpdateManifestException(UpdateManifestException.Reason.MALFORMED)
        }
        return validate(doc)
    }

    /**
     * @param currentVersionCode BuildConfig.VERSION_CODE ; @param sdkInt Build.VERSION.SDK_INT.
     * @throws UpdateManifestException
     */
    fun check(json: ByteArray, signature: ByteArray, currentVersionCode: Int, sdkInt: Int): UpdateCheckResult {
        val release = verify(json, signature)
        return when {
            release.versionCode <= currentVersionCode -> UpdateCheckResult.UpToDate
            release.minSdk > sdkInt -> UpdateCheckResult.Incompatible(release)
            else -> UpdateCheckResult.Available(release)
        }
    }

    private fun validate(doc: AppReleaseDocument): AppRelease {
        fun fail(r: UpdateManifestException.Reason): Nothing = throw UpdateManifestException(r)
        if (doc.schema !in SUPPORTED_SCHEMAS) fail(UpdateManifestException.Reason.UNKNOWN_SCHEMA)
        // versionCode Android = Int strictement positif.
        if (doc.versionCode !in 1..Int.MAX_VALUE.toLong()) fail(UpdateManifestException.Reason.BAD_VERSION)
        if (!UpdatePolicy.isValidVersionName(doc.versionName)) fail(UpdateManifestException.Reason.BAD_VERSION)
        if (doc.minSdk !in 1..1000) fail(UpdateManifestException.Reason.MALFORMED)
        if (!UpdatePolicy.isAllowedApkUrl(doc.apkUrl)) fail(UpdateManifestException.Reason.BAD_URL)
        if (!UpdatePolicy.isSha256Hex(doc.sha256)) fail(UpdateManifestException.Reason.BAD_SHA256)
        if (doc.size !in 1..UpdatePolicy.MAX_APK_BYTES) fail(UpdateManifestException.Reason.BAD_SIZE)
        if (doc.notes.length > UpdatePolicy.MAX_NOTES) fail(UpdateManifestException.Reason.MALFORMED)
        return AppRelease(
            versionCode = doc.versionCode.toInt(),
            versionName = doc.versionName,
            apkUrl = doc.apkUrl.toHttpUrlOrNull() ?: fail(UpdateManifestException.Reason.BAD_URL),
            sha256 = doc.sha256,
            size = doc.size,
            minSdk = doc.minSdk,
            // Texte affiche tel quel : caracteres de controle retires (sauf retour a la ligne).
            notes = doc.notes.filter { it == '\n' || !it.isISOControl() }.trim(),
        )
    }

    companion object {
        val SUPPORTED_SCHEMAS = setOf(1)
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
