package io.github.rubik1312.bandeja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AccionTest {
    // Domingo 1 de noviembre de 2026, 10:00
    private val ahora = LocalDateTime.of(2026, 11, 1, 10, 0)
    private fun ok(dicho: String, esperado: Accion) = assertEquals(dicho, esperado, Accion.interpretar(dicho, ahora))
    private fun dia(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun alarmas() {
        ok("pon una alarma a las 7:30", Accion.Alarma(19, 30, ""))
        ok("pon una alarma a las 7 de la mañana", Accion.Alarma(7, 0, ""))
        ok("alarma a las 22:40", Accion.Alarma(22, 40, ""))
        ok("despiértame a las siete y media de la tarde", Accion.Alarma(19, 30, ""))
        ok("pon alarma a las 8 menos cuarto de la noche", Accion.Alarma(19, 45, ""))
        ok("pon una alarma a las 6 de la mañana para ir al gym", Accion.Alarma(6, 0, "Ir al gym"))
        ok("pon una alarma en 20 minutos", Accion.Alarma(10, 20, ""))
        ok("pon una alarma a las 3 y treinta y tres de la madrugada", Accion.Alarma(3, 33, ""))
        ok("pon una alarma a las 3:33 de la madrugada", Accion.Alarma(3, 33, ""))
        ok("pon una alarma a las veintidós y cuarenta", Accion.Alarma(22, 40, ""))
        ok("pon una alarma", Accion.Falta("¿A qué hora pongo la alarma?"))
    }

    @Test fun alarmaDeOtroDiaVaAlCalendario() {
        ok("pon una alarma el jueves a las 7 de la mañana", Accion.Evento(dia(5, 7), "Alarma"))
    }

    @Test fun temporizador() {
        ok("pon un temporizador de 10 minutos", Accion.Temporizador(600, ""))
        ok("temporizador de media hora", Accion.Temporizador(1800, ""))
        ok("pon un temporizador de una hora y media", Accion.Temporizador(5400, ""))
        ok("pon un temporizador de 1 hora y 20 minutos", Accion.Temporizador(4800, ""))
        ok("pon un temporizador", Accion.Falta("¿De cuánto quieres el temporizador?"))
    }

    @Test fun cronometro() {
        ok("inicia el cronómetro", Accion.CronometroIniciar)
        ok("cronómetro", Accion.CronometroIniciar)
        ok("detén el cronómetro", Accion.CronometroDetener)
        ok("para el cronómetro", Accion.CronometroDetener)
        // El reconocedor puede cortar el verbo: con el cronómetro corriendo, se entiende que hay que detenerlo.
        assertEquals(Accion.CronometroDetener, Accion.interpretar("en el cronómetro", ahora, cronometroEnCurso = true))
        assertEquals(Accion.CronometroDetener, Accion.interpretar("cronómetro", ahora, cronometroEnCurso = true))
        assertEquals(Accion.CronometroDetener, Accion.interpretar("Vete en el cronómetro", ahora, cronometroEnCurso = true))
        assertTrue(Accion.interpretar("vete en el cronómetro", ahora, cronometroEnCurso = false) is Accion.Guardar)
        assertEquals(Accion.CronometroIniciar, Accion.interpretar("inicia el cronómetro", ahora, cronometroEnCurso = true))
        assertTrue(Accion.interpretar("comprar un cronómetro", ahora, cronometroEnCurso = true) is Accion.Guardar)
    }

    @Test fun eventos() {
        ok("recuérdame mañana a las 8 de la mañana revisar el informe", Accion.Evento(dia(2, 8), "Revisar el informe"))
        ok("recuérdame mañana a las 3 llamar al banco", Accion.Evento(dia(2, 15), "Llamar al banco"))
        ok("agrega al calendario reunión con Pedro el jueves a las 3 de la tarde", Accion.Evento(dia(5, 15), "Reunión con Pedro"))
        ok("agenda prueba de economía el 15 de noviembre a las 10 de la mañana", Accion.Evento(dia(15, 10), "Prueba de economía"))
        ok("recuérdame el viernes entregar el taller", Accion.Evento(dia(6, 9, 30), "Entregar el taller"))
        ok("recuérdame en 2 horas revisar el sorter", Accion.Evento(dia(1, 12), "Revisar el sorter"))
        ok("recuérdame hoy a las 5 de la tarde comprar proteína", Accion.Evento(dia(1, 17), "Comprar proteína"))
    }

    @Test fun recordatorioSinFechaQuedaComoIdea() {
        ok("recuérdame llamar al banco", Accion.Guardar(Comando.Idea(null, "Llamar al banco")))
    }

    @Test fun lasIdeasNoSeConfundenConComandos() {
        ok("guarda en trabajo revisar la alarma del sorter 5", Accion.Guardar(Comando.Idea("trabajo", "Revisar la alarma del sorter 5")))
        ok("anota comprar un cronómetro", Accion.Guardar(Comando.Idea(null, "Comprar un cronómetro")))
        ok("revisar la alarma del sorter", Accion.Guardar(Comando.Idea(null, "Revisar la alarma del sorter")))
        assertTrue(Accion.interpretar("automatizar el informe de turno", ahora) is Accion.Guardar)
    }

    @Test fun madrugadaConSuTurno() {
        // A las 23:00, "a las 8" es la mañana siguiente.
        assertEquals(Accion.Alarma(8, 0, ""), Accion.interpretar("pon una alarma a las 8", LocalDateTime.of(2026, 11, 1, 23, 0)))
        // A las 00:30, "a las 3" es esa misma madrugada.
        assertEquals(Accion.Alarma(3, 0, ""), Accion.interpretar("pon una alarma a las 3", LocalDateTime.of(2026, 11, 2, 0, 30)))
    }
}
