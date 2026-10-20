package fr.footballrama.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Le moteur regex d'Android (ICU) refuse le flag inline `U` (UNICODE_CHARACTER_CLASS), accepte par la JVM :
 * les tests JVM passaient alors que l'app plantait au chargement de `Matcher` sur le Z11 Pro.
 * Ce test bloque toute reintroduction dans le code de production (:core et :app).
 */
class AndroidRegexCompatTest {
    private val inlineFlags = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]""")
    private val unicodeClassFlag = Regex("""UNICODE_CHARACTER_CLASS""")

    @Test
    fun `no regex flag unsupported by Android ICU in production sources`() {
        val roots = listOf(File("src/main"), File("../app/src/main"))
        val offenders = roots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (inlineFlags.containsMatchIn(line) || unicodeClassFlag.containsMatchIn(line)) "${file.path}:${i + 1}" else null
                }
            }
        assertTrue(roots.first().isDirectory, "src/main introuvable (repertoire de travail inattendu)")
        assertTrue(offenders.isEmpty(), "Flag regex non supporte par Android : $offenders")
    }
}
