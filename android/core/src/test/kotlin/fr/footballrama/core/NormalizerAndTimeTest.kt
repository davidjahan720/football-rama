package fr.footballrama.core

import fr.footballrama.core.epg.XmltvTime
import fr.footballrama.core.text.Normalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NormalizerAndTimeTest {

    @Test
    fun `normalisation du contrat`() {
        assertEquals("fenerbahce sk", Normalizer.normalize("Fenerbahçe SK"))
        assertEquals("fk bodo glimt", Normalizer.normalize("FK Bodø/Glimt"))
        assertEquals("fc bayern munchen", Normalizer.normalize("FC Bayern München"))
        assertEquals("sk slovan bratislava", Normalizer.normalize("ŠK Slovan Bratislava"))
        assertEquals("ss oe ae l d i", Normalizer.normalize("ß Œ Æ Ł Đ ı"))
        assertEquals("club atletico de madrid", Normalizer.normalize("Club Atlético de Madrid"))
        assertEquals("paris saint germain fc", Normalizer.normalize("  Paris Saint-Germain FC!! "))
        assertEquals("brighton hove albion fc", Normalizer.normalize("Brighton & Hove Albion FC"))
        assertEquals("1", Normalizer.normalize("①")) // NFKD
        assertEquals("", Normalizer.normalize("★♣ —"))
        assertEquals("canal live 1", Normalizer.normalize("CANAL+ LIVE 1"))
    }

    @Test
    fun `recherche par mots entiers`() {
        assertTrue(Normalizer.containsPhrase("ligue 1 psg om", "psg"))
        assertTrue(Normalizer.containsPhrase("ligue 1 psg om", "om"))
        assertTrue(Normalizer.containsPhrase("paris saint germain barcelona", "paris saint germain"))
        assertFalse(Normalizer.containsPhrase("psgx om", "psg"))
        assertFalse(Normalizer.containsPhrase("roman", "om"))
        assertFalse(Normalizer.containsPhrase("ligue 12", "ligue 1"))
        assertFalse(Normalizer.containsPhrase("abc", ""))
        assertTrue(Normalizer.containsPhrase("xom om", "om")) // 2e occurrence
    }

    @Test
    fun `heures XMLTV avec fuseau`() {
        assertEquals(Instant.parse("2026-10-20T17:00:00Z"), XmltvTime.parse("20261020190000 +0200"))
        assertEquals(Instant.parse("2026-10-20T19:00:00Z"), XmltvTime.parse("20261020190000"))
        assertEquals(Instant.parse("2026-10-20T20:30:00Z"), XmltvTime.parse("20261020190000 -0130"))
        assertEquals(Instant.parse("2026-10-20T18:00:00Z"), XmltvTime.parse("20261020190000+0100"))
        assertNull(XmltvTime.parse("2026-10-20 19:00"))
        assertNull(XmltvTime.parse("20261340190000 +0000")) // mois 13
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
    }
}
