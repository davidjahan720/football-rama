package fr.footballrama.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.footballrama.app.R
import fr.footballrama.app.ui.logic.Age
import fr.footballrama.app.ui.logic.Countries
import fr.footballrama.core.matching.Score
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats d'affichage : francais, heure locale de l'appareil, 24 h. */
object Formats {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRENCH)

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun day(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        day.format(Instant.ofEpochMilli(epochMillis).atZone(zone)).replaceFirstChar { it.titlecase(Locale.FRENCH) }

    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateTime.format(instant.atZone(zone))
}

@Composable
fun updatedText(age: Age): String = when (age) {
    Age.JustNow -> stringResource(R.string.updated_just_now)
    is Age.Minutes -> pluralStringResource(R.plurals.updated_minutes_ago, age.value, age.value)
    is Age.Hours -> pluralStringResource(R.plurals.updated_hours_ago, age.value, age.value)
    is Age.Older -> stringResource(R.string.updated_on, Formats.dateTime(age.at))
}

@Composable
fun scoreLabel(score: Score): String = stringResource(if (score == Score.FORT) R.string.score_fort else R.string.score_moyen)

/** Libelle francais d'une cle pays (pur, voir [Countries.label]), utilisable hors composition (tri, Collator). */
@Composable
fun rememberCountryLabeler(): (String) -> String = remember { Countries::label }
