package fr.footballrama.core.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

/**
 * Programme renvoye par `player_api.php?action=get_short_epg` (ou `get_simple_data_table`), deja decode et borne.
 * [channelId] = `channel_id` du panel (informatif : le repli utilise l'epg_channel_id du flux interroge).
 */
data class EpgListing(
    val channelId: String,
    val start: Instant,
    val stop: Instant?,
    val title: String,
    val description: String,
)

/**
 * Parsing defensif des reponses EPG par chaine des panels Xtream. Format observe :
 * `{"epg_listings":[{"title":"<base64>","description":"<base64>","channel_id":"X.fr",
 * "start_timestamp":"1792667700","stop_timestamp":"1792674000", ...}]}`.
 * Variantes acceptees : timestamps texte ou nombre, champs manquants, `epg_listings` absent / objet / vide,
 * reponse `[]`, `{}` ou corps vide. `{"user_info":{"auth":0}}` = identifiants refuses.
 */
object ShortEpgParser {
    const val MAX_TITLE = 300
    const val MAX_DESCRIPTION = 1_000
    const val MAX_LISTINGS = 2_000
    private const val MAX_ID = 300

    /** 2000-01-01 .. 2100-01-01 : en dehors, la valeur est consideree invalide. */
    private const val MIN_EPOCH_S = 946_684_800L
    private const val MAX_EPOCH_S = 4_102_444_800L

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * @throws XtreamException AUTH si le panel signale des identifiants refuses, INVALID_RESPONSE si la reponse
     *   n'est pas du JSON (page HTML d'erreur...).
     */
    fun parse(text: String): List<EpgListing> {
        val trimmed = text.trim().removePrefix("﻿").trim()
        if (trimmed.isEmpty()) return emptyList()
        // Le lecteur d'arbre kotlinx accepte un litteral non quote en racine : page HTML -> refus explicite.
        if (trimmed[0] != '[' && trimmed[0] != '{') throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
        val root = try {
            JSON.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            throw XtreamException(XtreamException.Kind.INVALID_RESPONSE)
        }
        val items: Collection<JsonElement> = when (root) {
            is JsonArray -> root // [] (aucun programme) ou, sur certains panels, la liste directement
            is JsonObject -> {
                val auth = (root["user_info"] as? JsonObject)?.str("auth")
                if (auth == "0") throw XtreamException(XtreamException.Kind.AUTH)
                when (val l = root["epg_listings"]) {
                    is JsonArray -> l
                    is JsonObject -> l.values // {"0":{...},"1":{...}} ou {}
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
        val out = ArrayList<EpgListing>(minOf(items.size, MAX_LISTINGS))
        for (el in items) {
            if (out.size >= MAX_LISTINGS) break
            val o = el as? JsonObject ?: continue
            val start = o.epoch("start_timestamp") ?: continue
            val stop = o.epoch("stop_timestamp") ?: o.epoch("end_timestamp")
            out.add(
                EpgListing(
                    channelId = (o.str("channel_id") ?: "").trim().take(MAX_ID),
                    start = start,
                    stop = stop?.takeIf { it.isAfter(start) },
                    title = decodeText(o.str("title")).take(MAX_TITLE),
                    description = decodeText(o.str("description")).take(MAX_DESCRIPTION),
                ),
            )
        }
        return out
    }

    /**
     * Texte base64 du panel. Le contenu decode n'est pas toujours en UTF-8 (constate : ISO-8859-1/Windows-1252) :
     * UTF-8 strict d'abord, sinon Windows-1252. Une valeur qui n'est pas du base64 est gardee telle quelle.
     */
    fun decodeText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val compact = raw.filterNot { it == '\n' || it == '\r' || it == ' ' }
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            return clean(raw)
        }
        return clean(decodeBytes(bytes))
    }

    fun decodeBytes(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    /** Remplace les caracteres de controle par des espaces (texte destine a l'affichage et au matching). */
    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c < ' ' || c == '\u007F') ' ' else c)
        return sb.toString().trim()
    }

    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    /** Epoch en secondes, texte ou nombre (tolere un epoch en millisecondes). */
    private fun JsonObject.epoch(key: String): Instant? {
        val raw = str(key)?.trim() ?: return null
        var s = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: return null
        if (s > MAX_EPOCH_S * 10) s /= 1000 // millisecondes
        if (s !in MIN_EPOCH_S..MAX_EPOCH_S) return null
        return Instant.ofEpochSecond(s)
    }
}
