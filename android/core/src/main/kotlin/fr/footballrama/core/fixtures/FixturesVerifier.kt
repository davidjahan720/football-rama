package fr.footballrama.core.fixtures

import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.util.Bytes
import fr.footballrama.core.text.Normalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

class FixturesException(val reason: Reason) : Exception("fixtures rejected: $reason") {
    enum class Reason {
        /** Aucune cle publique exploitable dans le build (placeholder). */
        NO_PUBLIC_KEY,
        /** Signature invalide : peut etre une incoherence passagere du CDN (json et .sig de 2 versions). */
        BAD_SIGNATURE,
        MALFORMED,
        UNKNOWN_SCHEMA,
        EXPIRED,
        ROLLBACK,
        TOO_LARGE,
    }
}

/**
 * Verifie fixtures.json + fixtures.json.sig selon le contrat :
 * signature Ed25519 sur les OCTETS EXACTS (une des cles acceptees, pour la rotation), puis schema connu,
 * valid_until non depasse, generated_at >= celui du dernier fichier accepte (anti-rollback, fourni par l'appelant).
 *
 * Choix : Google Tink (tink-android, Java pur, fonctionne sur JVM et Android des minSdk 26) car
 * l'Ed25519 du JCA n'existe sur Android qu'a partir de l'API 33. Tink est maintenu par Google,
 * implemente RFC 8032 et rejette les signatures non canoniques.
 */
class FixturesVerifier(
    publicKeysBase64: List<String>,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val verifiers: List<PublicKeyVerify> = publicKeysBase64.mapNotNull { key ->
        runCatching {
            val raw = Base64.getDecoder().decode(key.trim())
            require(raw.size == Ed25519Verify.PUBLIC_KEY_LEN)
            Ed25519Verify.create(Ed25519PublicKey.create(Bytes.copyFrom(raw)))
        }.getOrNull()
    }

    /** Vrai si au moins une cle embarquee est exploitable (faux avec la valeur placeholder du build). */
    val isConfigured: Boolean get() = verifiers.isNotEmpty()

    /**
     * @param previousGeneratedAt generated_at du dernier fichier accepte (null si aucun) ; un fichier plus ancien est refuse.
     * @throws FixturesException
     */
    fun verify(json: ByteArray, signature: ByteArray, previousGeneratedAt: Instant?): Fixtures {
        if (verifiers.isEmpty()) throw FixturesException(FixturesException.Reason.NO_PUBLIC_KEY)
        if (json.size > MAX_JSON_BYTES || signature.size > MAX_SIG_BYTES) {
            throw FixturesException(FixturesException.Reason.TOO_LARGE)
        }
        val sig = try {
            Base64.getDecoder().decode(String(signature, Charsets.US_ASCII).trim())
        } catch (_: IllegalArgumentException) {
            throw FixturesException(FixturesException.Reason.BAD_SIGNATURE)
        }
        if (sig.size != Ed25519Verify.SIGNATURE_LEN) throw FixturesException(FixturesException.Reason.BAD_SIGNATURE)
        val valid = verifiers.any { v ->
            try {
                v.verify(sig, json); true
            } catch (_: GeneralSecurityException) {
                false
            }
        }
        if (!valid) throw FixturesException(FixturesException.Reason.BAD_SIGNATURE)

        // Parsing seulement apres verification : on ne traite pas de donnees non authentifiees.
        val doc = try {
            JSON.decodeFromString(FixturesDocument.serializer(), json.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw FixturesException(FixturesException.Reason.MALFORMED)
        } catch (_: IllegalArgumentException) {
            throw FixturesException(FixturesException.Reason.MALFORMED)
        }
        if (doc.schema !in SUPPORTED_SCHEMAS) throw FixturesException(FixturesException.Reason.UNKNOWN_SCHEMA)
        val fixtures = toDomain(doc)
        if (!clock.instant().isBefore(fixtures.validUntil)) throw FixturesException(FixturesException.Reason.EXPIRED)
        if (previousGeneratedAt != null && fixtures.generatedAt.isBefore(previousGeneratedAt)) {
            throw FixturesException(FixturesException.Reason.ROLLBACK)
        }
        return fixtures
    }

    private fun toDomain(doc: FixturesDocument): Fixtures {
        if (doc.matches.size > MAX_MATCHES) throw FixturesException(FixturesException.Reason.MALFORMED)
        try {
            val generatedAt = Instant.parse(doc.generatedAt)
            val validUntil = Instant.parse(doc.validUntil)
            val from = Instant.parse(doc.window.from)
            val to = Instant.parse(doc.window.to)
            if (!validUntil.isAfter(generatedAt) || !to.isAfter(from)) {
                throw FixturesException(FixturesException.Reason.MALFORMED)
            }
            return Fixtures(
                schema = doc.schema,
                generatedAt = generatedAt,
                validUntil = validUntil,
                windowFrom = from,
                windowTo = to,
                competitions = doc.competitions,
                competitionKeywords = doc.matching.competitionKeywords.mapValues { (_, v) -> cleanTerms(v, 1) },
                excludedTitleWords = cleanTerms(doc.matching.excludedTitleWords, 1),
                matches = doc.matches.map { m ->
                    Fixture(m.id, m.competition.take(MAX_NAME), Instant.parse(m.kickoffUtc), team(m.home), team(m.away))
                },
            )
        } catch (_: DateTimeParseException) {
            throw FixturesException(FixturesException.Reason.MALFORMED)
        }
    }

    private fun team(t: TeamDto) = Team(
        name = t.name.take(MAX_NAME),
        short = t.short?.take(MAX_NAME),
        // Un alias d'un seul caractere matcherait n'importe quoi : ecarte (le publisher impose >= 2).
        aliases = cleanTerms(t.aliases, 2),
        tokens = cleanTerms(t.tokens, 2),
    )

    private fun cleanTerms(terms: List<String>, minLength: Int): List<String> =
        terms.asSequence().take(MAX_TERMS).map { Normalizer.normalize(it.take(MAX_NAME)) }
            .filter { it.length >= minLength }.distinct().toList()

    companion object {
        val SUPPORTED_SCHEMAS = setOf(1)
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_SIG_BYTES = 1024
        private const val MAX_MATCHES = 1000
        private const val MAX_TERMS = 64
        private const val MAX_NAME = 200
        internal val JSON = Json { ignoreUnknownKeys = true }

        /** "k1,k2" (BuildConfig.FIXTURES_PUBLIC_KEYS) -> liste de cles. */
        fun parseKeyList(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
