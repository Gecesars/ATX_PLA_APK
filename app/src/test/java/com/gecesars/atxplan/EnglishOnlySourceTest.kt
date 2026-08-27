package com.gecesars.atxplan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishOnlySourceTest {
    @Test
    fun `production sources do not contain common Portuguese UI terms`() {
        val sourceRoot = locateProductionSourceRoot()
        val violations = sourceRoot.walkTopDown()
            .filter { file ->
                file.isFile && file.extension.lowercase() in setOf("kt", "xml", "json", "txt")
            }
            .mapNotNull { file ->
                val text = file.readText().withoutApprovedSourceIdentifiers()
                portugueseUiTerm.find(text)?.let { match ->
                    "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: '${match.value}'"
                }
            }
            .toList()

        assertTrue(
            "Production UI sources must remain English-only. Found: ${violations.joinToString()}",
            violations.isEmpty(),
        )
    }

    private fun locateProductionSourceRoot(): File =
        sequenceOf(File("src/main"), File("app/src/main"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate the Android production source directory.")

    private fun String.withoutApprovedSourceIdentifiers(): String =
        replace(officialUrl, "")
            .replace("BR_setores_CD2022.zip", "")

    private companion object {
        val portugueseUiTerm = Regex(
            """(?iu)(?<!\p{L})(?:
                projetos?|cat[aá]logo|armazenamento|vis[aã]o|in[ií]cio|mapa|estudos?|
                engenharia|criar|novo|nome|cliente|selecionad[oa]|redes?|setores?|
                frequ[eê]ncia|dist[aâ]ncia|pot[eê]ncia|ru[ií]do|perdas?|ganho|
                largura|c[aá]lculos?|calcular|enlace|resultados?|margem|sensibilidade|
                piso|caminho|terreno|etapa|planejad[oa]|funda[cç][aã]o|cobertura|
                interfer[eê]ncia|popula[cç][aã]o|azimutes?|salvar|falha|inv[aá]lid[oa]|
                somente|nenhum[ao]?|a[cç][oõ]es|radiodifus[aã]o|atualizad[oa]|padr[aã]o|
                propaga[cç][aã]o|brasil|dispon[ií]vel|inconclusiv[oa]|arquivo|edif[ií]cios|
                licen[cç]a|retomada|or[cç]amento|par[aâ]metros?|sint[eé]tic[oa]|
                demonstra[cç][aã]o|operadora|serra|centro|noroeste|n[aã]o|sem
            )(?!\p{L})""".replace(Regex("\\s+"), ""),
        )
        val officialUrl = Regex("https://[^\\s\\\"]+")
    }
}
