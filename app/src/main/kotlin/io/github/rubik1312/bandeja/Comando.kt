package io.github.rubik1312.bandeja

import java.text.Normalizer

/**
 * Interpreta lo dictado después de "Oye Jarvis": "guarda en trabajo la siguiente idea: revisar el sorter"
 * → sección "trabajo" y texto "Revisar el sorter". Si no se nombra sección, la deja en null (la ordena Claude).
 */
object Comando {

    data class Idea(val categoria: String?, val texto: String)

    private val SECCIONES = listOf(
        "trabajo" to "trabajo|pega",
        "universidad" to "universidad|la u|u|facultad|estudios",
        "personal" to "personal",
        "proyectos" to "proyectos?",
    )
    private val SECCION = SECCIONES.joinToString("|") { "(?:${it.second})" }

    // Todo se compara sobre el texto sin tildes y en minúsculas (mismo largo que el original).
    private val RESTO_JARVIS = Regex("^(?:oye\\s+|hey\\s+)?jarvis\\b[\\s,.:]*")
    private val VERBO = Regex(
        "^(?:por favor\\s+)?(?:guarda(?:r|lo|la|me)?|guarde|anota(?:r|lo|la)?|anote|apunta(?:r|lo|la)?|apunte|" +
            "agrega(?:r|lo|la)?|agregue|pon(?:er|lo|la)?|crea(?:r)?|nueva|nuevo|registra(?:r)?)\\b[\\s,.:]*"
    )
    private val RELLENO = Regex(
        "^(?:(?:esta|una|la siguiente|siguiente|nueva)\\s+(?:idea|nota|tarea)|lo siguiente|esto|que|idea|nota|tarea)\\b[\\s,.:]*"
    )
    // La sección necesita "en/para" o "sección" delante, para no confundir "anota trabajo práctico…" con la sección Trabajo.
    private const val ANTES = "(?:(?:la\\s+)?seccion\\s+(?:de\\s+)?|el\\s+|la\\s+|lo\\s+|los\\s+|mis?\\s+)?"
    private val SECCION_INICIO = Regex(
        "^(?:(?:en|para|a)\\s+$ANTES|(?:la\\s+)?seccion\\s+(?:de\\s+)?)($SECCION)\\b[\\s,.:]*"
    )
    private val SECCION_FINAL = Regex("[\\s,]+(?:en|para)\\s+$ANTES($SECCION)[\\s.!]*$")

    fun interpretar(dicho: String): Idea {
        val original = Normalizer.normalize(dicho.trim(), Normalizer.Form.NFC)
        val w = Normalizer.normalize(original, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "").lowercase()
        // Si quitar tildes cambiara el largo (caracteres raros), se trabaja sin mapear posiciones.
        val base = if (w.length == original.length) original else w
        var pos = 0
        var categoria: String? = null

        fun quitar(r: Regex): Boolean {
            val m = r.find(w.substring(pos)) ?: return false
            if (m.value.isEmpty()) return false
            pos += m.value.length
            return true
        }

        quitar(RESTO_JARVIS)
        var avanzo = true
        while (avanzo && pos < w.length) {
            avanzo = false
            if (quitar(VERBO)) avanzo = true
            if (quitar(RELLENO)) avanzo = true
            if (categoria == null) {
                val m = SECCION_INICIO.find(w.substring(pos))
                if (m != null && m.value.isNotEmpty()) {
                    categoria = seccionDe(m.groupValues[1])
                    pos += m.value.length
                    avanzo = true
                }
            }
        }

        var fin = w.length
        if (categoria == null) {
            SECCION_FINAL.find(w, pos)?.let { m ->
                categoria = seccionDe(m.groupValues[1])
                fin = m.range.first
            }
        }

        val texto = corregir(base.substring(pos.coerceAtMost(fin), fin).trim().trimEnd('.', ','))
        return Idea(categoria, texto.replaceFirstChar { it.uppercase() })
    }

    // Palabras de su trabajo que el reconocedor de Google suele entender mal.
    private val CORRECCIONES = listOf(
        Regex("\\bshorter(s)?\\b", RegexOption.IGNORE_CASE) to "sorter$1",
        Regex("\\bsortear\\b", RegexOption.IGNORE_CASE) to "sorter",
    )

    private fun corregir(t: String) = CORRECCIONES.fold(t) { acc, (r, por) -> r.replace(acc, por) }

    private fun seccionDe(palabra: String): String? {
        val p = palabra.trim()
        return SECCIONES.firstOrNull { Regex("^(?:${it.second})$").matches(p) }?.first
    }

    fun nombre(categoria: String?) = when (categoria) {
        "trabajo" -> "Trabajo"
        "universidad" -> "Universidad"
        "personal" -> "Personal"
        "proyectos" -> "Proyectos"
        else -> "la bandeja"
    }
}
