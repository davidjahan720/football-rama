package fr.footballrama.core.epg

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Equivalent de `parse_xmltv_time` : "20261020190000 +0200" -> Instant UTC. Fuseau absent = UTC. */
object XmltvTime {

    private val PATTERN = Regex("""^(\d{14})\s*([+-]\d{4})?""")

    fun parse(value: String?): Instant? {
        if (value.isNullOrEmpty()) return null
        val m = PATTERN.find(value) ?: return null
        val d = m.groupValues[1]
        val tz = m.groupValues[2]
        return try {
            val local = LocalDateTime.of(
                d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
                d.substring(8, 10).toInt(), d.substring(10, 12).toInt(), d.substring(12, 14).toInt(),
            )
            val offset = if (tz.isEmpty()) {
                ZoneOffset.UTC
            } else {
                val sign = if (tz[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(sign * tz.substring(1, 3).toInt(), sign * tz.substring(3, 5).toInt())
            }
            local.toInstant(offset)
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}
