package io.github.rubik1312.bandeja

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Decide qué hacer con lo dictado después de "Oye Jarvis": alarma, temporizador, cronómetro,
 * evento de calendario o, si nada de eso calza, guardar una idea (ver [Comando]).
 * No toca Android, así se puede probar con cualquier fecha y hora.
 */
sealed class Accion {
    data class Alarma(val hora: Int, val minuto: Int, val etiqueta: String) : Accion()
    data class Temporizador(val segundos: Int, val etiqueta: String) : Accion()
    object CronometroIniciar : Accion()
    object CronometroDetener : Accion()
    data class Evento(val inicio: LocalDateTime, val titulo: String) : Accion()
    data class Guardar(val idea: Comando.Idea) : Accion()
    data class Falta(val mensaje: String) : Accion()

    companion object {
        /** Sin fecha ni hora de partida, "mañana revisar X" queda a la salida del turno. */
        private val HORA_POR_DEFECTO = LocalTime.of(9, 30)

        private val UNIDADES = listOf("uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve")

        // De mayor a menor largo: primero "treinta y tres", para que no quede en "30 y 3".
        private val NUMEROS = buildList {
            for ((decena, valor) in listOf("treinta" to 30, "cuarenta" to 40, "cincuenta" to 50)) {
                UNIDADES.forEachIndexed { i, u -> add("$decena y $u" to "${valor + i + 1}") }
            }
            addAll(listOf("veintiuno" to "21", "veintidos" to "22", "veintidós" to "22", "veintitres" to "23", "veintitrés" to "23",
                "veinticuatro" to "24", "veinticinco" to "25", "veintiseis" to "26", "veintiséis" to "26", "veintisiete" to "27",
                "veintiocho" to "28", "veintinueve" to "29", "dieciseis" to "16", "dieciséis" to "16", "diecisiete" to "17",
                "dieciocho" to "18", "diecinueve" to "19", "trece" to "13", "catorce" to "14", "cincuenta" to "50", "cuarenta" to "40",
                "treinta" to "30", "veinte" to "20", "quince" to "15", "doce" to "12", "once" to "11", "diez" to "10"))
            UNIDADES.drop(1).reversed().forEachIndexed { i, u -> add(u to "${9 - i}") }
        }.map { (p, n) -> Regex("\\b$p\\b", RegexOption.IGNORE_CASE) to n }

        private const val VERBO = "(?:por favor\\s+)?(?:(?:pon|ponme|poner|programa|programame|programar|crea|crear|activa|activar|configura|configurar|" +
            "agrega|agregar|inicia|iniciar|empieza|empezar|comienza|comenzar|parte|partir|arranca|arrancar|quiero)\\s+)?(?:(?:una?|la|el|mi|nueva|nuevo)\\s+)*"

        private val GUARDAR = Regex("^(?:por favor\\s+)?(?:guarda|guardar|guarde|anota|anotar|anote|apunta|apuntar|apunte)\\b")
        private val ALARMA = Regex("^(?:${VERBO}(?:alarma|despertador)\\b|despiertame\\b|despertarme\\b)")
        private val TEMPORIZADOR = Regex("^$VERBO(?:temporizador|timer|cuenta regresiva)\\b")
        private val CRONO_DETENER = Regex("^(?:por favor\\s+)?(?:deten|detener|detenga|detengan|dejen|deje|para|parar|pare|termina|terminar|acaba|acabar|finaliza|finalizar|apaga|apagar|frena|frenar|stop|alto|cierra|cerrar)\\s+(?:(?:el|mi)\\s+)?cronometro\\b")
        private val CRONO_INICIAR = Regex("^${VERBO}cronometro\\b")
        // Solo la palabra (a veces el reconocedor corta el verbo: "detén el…" llega como "en el cronómetro").
        private val CRONO_SOLO = Regex("^(?:(?:en|el|mi|al|ya|y)\\s+)*cronometro[.!]*$")
        // Con el cronómetro corriendo, un verbo mal entendido ("vete en el cronómetro") se toma como detenerlo.
        private val CRONO_VERBO_RARO = Regex("^\\S+\\s+(?:en\\s+)?(?:el|mi)\\s+cronometro[.!]*$")
        private val EVENTO = Regex("^(?:por favor\\s+)?(?:recuerdame|recordarme|recuerda|recordatorio|agenda|agendame|agendar|crea un evento|crear un evento|evento)\\b|\\bcalendario\\b")

        private val PREFIJO_EVENTO = Regex(
            "^(?:por favor\\s+)?(?:(?:recuerdame|recordarme|recuerda|agendame|agendar|agenda|anota|anotar|agrega|agregar|pon|poner|ponme|crea|crear|guarda|guardar)\\s+)?" +
                "(?:(?:un|una|el|la|en|al|mi)\\s+)*(?:recordatorio|evento|calendario)?\\s*(?:(?:en|al)\\s+(?:el\\s+|mi\\s+)?calendario\\s*)?(?:(?:de|para|que|a)\\s+)?"
        )
        private val PREFIJO_ALARMA = Regex("^$VERBO(?:alarma|despertador|despiertame|despertarme)\\b\\s*(?:(?:para|de)\\s+)?")
        private val PREFIJO_TEMPORIZADOR = Regex("^$VERBO(?:temporizador|timer|cuenta regresiva)\\b\\s*(?:(?:para|de)\\s+)?")

        private val HORA = Regex(
            "\\b(?:para\\s+)?a\\s+las?\\s+(\\d{1,2})(?:[:.](\\d{2})|\\s+y\\s+(media|cuarto|\\d{1,2})|\\s+menos\\s+(cuarto|\\d{1,2}))?(?:\\s*(?:horas?|hrs?)\\b)?" +
                "(?:\\s*(de la manana|de la madrugada|de la tarde|de la noche|a\\.?\\s?m\\b\\.?|p\\.?\\s?m\\b\\.?))?"
        )
        private val MEDIODIA = Regex("\\b(?:(?:para\\s+)?a\\s+|al\\s+)(mediodia|medianoche)\\b")
        private val EN_TIEMPO = Regex("\\b(?:para\\s+)?(?:en|dentro de)\\s+((?:media hora|(?:\\d+|una?)\\s+(?:horas?|minutos?|min|segundos?)(?:\\s+y\\s+(?:media|\\d+\\s+(?:minutos?|segundos?)))?))\\b")
        private val DURACION = Regex("\\b(media hora|(?:\\d+|una?)\\s+(?:horas?|minutos?|min|segundos?)(?:\\s+y\\s+(?:media|\\d+\\s+(?:minutos?|segundos?)))?)\\b")
        private val DIA_MES = Regex("\\bel\\s+(\\d{1,2})(?:\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)|[/-](\\d{1,2}))?\\b")
        private val DIA_SEMANA = Regex("\\b(?:(?:el|este)\\s+)?(?:proximo\\s+)?(lunes|martes|miercoles|jueves|viernes|sabado|domingo)(?:\\s+(?:que viene|proximo))?\\b")
        private val DIA_RELATIVO = Regex("\\b(?:para\\s+)?(pasado manana|manana|hoy)\\b")

        private val DIAS = mapOf(
            "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY, "jueves" to DayOfWeek.THURSDAY,
            "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY,
        )
        private val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

        fun interpretar(dicho: String, ahora: LocalDateTime = LocalDateTime.now(), cronometroEnCurso: Boolean = false): Accion {
            var t = Normalizer.normalize(dicho.trim(), Normalizer.Form.NFC)
                .replace(Regex("^(?:oye\\s+|hey\\s+)?jarvis\\b[\\s,.:]*", RegexOption.IGNORE_CASE), "")
            for ((r, n) in NUMEROS) t = r.replace(t, n)
            t = t.replace(Regex("\\ba\\s+la\\s+una\\b", RegexOption.IGNORE_CASE), "a las 1")
            val w = sinTildes(t)

            val aCalendario = w.contains("calendario")
            if (GUARDAR.containsMatchIn(w) && !aCalendario) return Accion.Guardar(Comando.interpretar(dicho))
            if (CRONO_DETENER.containsMatchIn(w)) return CronometroDetener
            if (CRONO_SOLO.matches(w)) return if (cronometroEnCurso) CronometroDetener else CronometroIniciar
            if (CRONO_INICIAR.containsMatchIn(w)) return CronometroIniciar
            if (cronometroEnCurso && CRONO_VERBO_RARO.matches(w)) return CronometroDetener
            if (TEMPORIZADOR.containsMatchIn(w)) return temporizador(t, w)
            if (ALARMA.containsMatchIn(w)) return alarma(t, w, ahora)
            if (EVENTO.containsMatchIn(w)) return evento(t, w, ahora, dicho)
            return Guardar(Comando.interpretar(dicho))
        }

        private fun sinTildes(s: String): String {
            val w = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase()
            return if (w.length == s.length) w else s.lowercase()
        }

        private fun temporizador(t: String, w: String): Accion {
            val m = DURACION.find(w) ?: return Falta("¿De cuánto quieres el temporizador?")
            val etiqueta = limpiar(borrar(t, listOf(m.range)), borrar(w, listOf(m.range)), PREFIJO_TEMPORIZADOR)
            return Temporizador(segundos(m.groupValues[1]), etiqueta)
        }

        private fun alarma(t: String, w: String, ahora: LocalDateTime): Accion {
            val tiempo = leerTiempo(w) ?: EN_TIEMPO.find(w)?.let { m ->
                val a = ahora.plusSeconds(segundos(m.groupValues[1]).toLong())
                val etiqueta = limpiar(borrar(t, listOf(m.range)), borrar(w, listOf(m.range)), PREFIJO_ALARMA)
                return Alarma(a.hour, a.minute, etiqueta)
            } ?: return Falta("¿A qué hora pongo la alarma?")
            val hueco = sinDia(tiempo.rango, w)
            val cuando = resolver(tiempo, hueco.dia?.invoke(ahora), ahora)
            val etiqueta = limpiar(borrar(t, hueco.rangos), borrar(w, hueco.rangos), PREFIJO_ALARMA)
            // El reloj solo programa "la próxima vez que llegue esa hora"; para otro día, mejor un evento con aviso.
            val proxima = ahora.toLocalDate().atTime(cuando.toLocalTime()).let { if (it.isAfter(ahora)) it else it.plusDays(1) }
            return if (cuando == proxima) Alarma(cuando.hour, cuando.minute, etiqueta)
            else Evento(cuando, etiqueta.ifEmpty { "Alarma" })
        }

        private fun evento(t: String, w: String, ahora: LocalDateTime, dicho: String): Accion {
            val tiempo = leerTiempo(w)
            val relativo = if (tiempo == null) EN_TIEMPO.find(w) else null
            if (relativo != null) {
                val titulo = limpiar(borrar(t, listOf(relativo.range)), borrar(w, listOf(relativo.range)), PREFIJO_EVENTO)
                if (titulo.isEmpty()) return Falta("¿Qué te recuerdo?")
                return Evento(ahora.plusSeconds(segundos(relativo.groupValues[1]).toLong()).truncatedTo(ChronoUnit.MINUTES), titulo)
            }
            val hueco = sinDia(tiempo?.rango, w)
            val dia = hueco.dia?.invoke(ahora)
            if (tiempo == null && dia == null) {
                // Sin fecha ni hora no hay nada que poner en el calendario: queda como idea en la bandeja.
                val titulo = limpiar(t, w, PREFIJO_EVENTO)
                return if (titulo.isEmpty()) Falta("¿Qué te recuerdo?") else Guardar(Comando.Idea(null, titulo))
            }
            val cuando = if (tiempo != null) resolver(tiempo, dia, ahora) else dia!!.atTime(HORA_POR_DEFECTO)
            val titulo = limpiar(borrar(t, hueco.rangos), borrar(w, hueco.rangos), PREFIJO_EVENTO)
            return if (titulo.isEmpty()) Falta("¿Qué te recuerdo?") else Evento(cuando, titulo)
        }

        private class Tiempo(val hora: Int, val minuto: Int, val ambigua: Boolean, val rango: IntRange)
        private class Hueco(val rangos: List<IntRange>, val dia: ((LocalDateTime) -> LocalDate)?)

        private fun leerTiempo(w: String): Tiempo? {
            MEDIODIA.find(w)?.let { return Tiempo(if (it.groupValues[1] == "mediodia") 12 else 0, 0, false, it.range) }
            val m = HORA.find(w) ?: return null
            var h = m.groupValues[1].toInt()
            var min = m.groupValues[2].toIntOrNull() ?: 0
            when (val y = m.groupValues[3]) { "" -> {}; "media" -> min = 30; "cuarto" -> min = 15; else -> min = y.toInt() }
            val menos = m.groupValues[4]
            if (menos.isNotEmpty()) { h -= 1; min = 60 - if (menos == "cuarto") 15 else menos.toInt() }
            val parte = m.groupValues[5].replace(Regex("[.\\s]"), "")
            if (h !in 0..24 || min !in 0..59) return null
            val tarde = parte in setOf("delatarde", "delanoche", "pm")
            val temprano = parte in setOf("delamanana", "delamadrugada", "am")
            if (tarde && h < 12) h += 12
            if (temprano && h == 12) h = 0
            if (h == 24) h = 0
            return Tiempo(h, min, !tarde && !temprano && h in 1..12, m.range)
        }

        /** Busca el día en el texto, sin confundir "de la mañana" (parte de la hora) con "mañana". */
        private fun sinDia(rangoHora: IntRange?, w: String): Hueco {
            val rangos = listOfNotNull(rangoHora).toMutableList()
            val sinHora = borrar(w, rangos)
            DIA_RELATIVO.find(sinHora)?.let { m ->
                rangos += m.range
                val mas = when (m.groupValues[1]) { "hoy" -> 0L; "manana" -> 1L; else -> 2L }
                return Hueco(rangos) { it.toLocalDate().plusDays(mas) }
            }
            DIA_MES.find(sinHora)?.let { m ->
                rangos += m.range
                val d = m.groupValues[1].toInt()
                val mesNombre = MESES.indexOf(m.groupValues[2].replace("setiembre", "septiembre")) + 1
                val mesNumero = m.groupValues[3].toIntOrNull()
                return Hueco(rangos) { a -> fechaDesde(a.toLocalDate(), d, if (mesNombre > 0) mesNombre else mesNumero) }
            }
            DIA_SEMANA.find(sinHora)?.let { m ->
                rangos += m.range
                val dia = DIAS.getValue(m.groupValues[1])
                return Hueco(rangos) { it.toLocalDate().with(TemporalAdjusters.nextOrSame(dia)) }
            }
            return Hueco(rangos, null)
        }

        private fun fechaDesde(hoy: LocalDate, d: Int, mes: Int?): LocalDate {
            fun valida(y: Int, m: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
            if (mes != null) {
                val f = valida(hoy.year, mes) ?: return hoy
                return if (f.isBefore(hoy)) f.plusYears(1) else f
            }
            var c = hoy.withDayOfMonth(1)
            repeat(13) {
                val f = valida(c.year, c.monthValue)
                if (f != null && !f.isBefore(hoy)) return f
                c = c.plusMonths(1)
            }
            return hoy
        }

        /** Sin "de la tarde" ni similar: hoy, la próxima vez que llegue esa hora; otro día, de 1 a 6 se entiende de tarde. */
        private fun resolver(t: Tiempo, dia: LocalDate?, ahora: LocalDateTime): LocalDateTime {
            val base = LocalTime.of(t.hora, t.minuto)
            if (dia == null || dia == ahora.toLocalDate()) {
                val hoy = ahora.toLocalDate()
                val candidatos = mutableListOf(hoy.atTime(base))
                if (t.ambigua) candidatos += hoy.atTime(base).plusHours(12)
                val siguiente = candidatos.filter { it.isAfter(ahora) }.minOrNull()
                if (siguiente != null) return siguiente
                val manana = candidatos.min().plusDays(1)
                return if (dia == null) manana else hoy.atTime(base).plusDays(1)
            }
            val h = if (t.ambigua && t.hora in 1..6) t.hora + 12 else t.hora
            return dia.atTime(h, t.minuto)
        }

        private fun segundos(texto: String): Int {
            if (texto == "media hora") return 1800
            var total = 0
            val partes = Regex("(\\d+|una?)\\s+(hora|minuto|min|segundo)s?(?:\\s+y\\s+media)?").findAll(texto)
            for (p in partes) {
                val n = p.groupValues[1].toIntOrNull() ?: 1
                val unidad = when (p.groupValues[2]) { "hora" -> 3600; "segundo" -> 1; else -> 60 }
                total += n * unidad
                if (p.value.endsWith("y media")) total += unidad / 2
            }
            return total
        }

        private fun borrar(s: String, rangos: List<IntRange>): String {
            val c = s.toCharArray()
            for (r in rangos) for (i in r) if (i in c.indices) c[i] = ' '
            return String(c)
        }

        /** Saca las palabras de comando del inicio y deja el título con la primera letra en mayúscula. */
        private fun limpiar(t: String, w: String, prefijo: Regex): String {
            val inicio = prefijo.find(w.trimStart())?.value?.length ?: 0
            val desde = w.length - w.trimStart().length + inicio
            return t.substring(desde.coerceAtMost(t.length))
                .replace(Regex("\\s+"), " ").trim().trim(',', '.', ':')
                .replace(Regex("^(?:de|para|que|a)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:de|para|que|a|en)$", RegexOption.IGNORE_CASE), "")
                .trim().let(Comando::corregir).replaceFirstChar { it.uppercase() }
        }
    }
}
