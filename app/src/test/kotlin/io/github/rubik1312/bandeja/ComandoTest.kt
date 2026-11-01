package io.github.rubik1312.bandeja

import org.junit.Assert.assertEquals
import org.junit.Test

class ComandoTest {
    private fun ok(dicho: String, categoria: String?, texto: String) =
        assertEquals(dicho, Comando.Idea(categoria, texto), Comando.interpretar(dicho))

    @Test fun seccionAlInicio() {
        ok("guarda en trabajo la siguiente idea revisar el sorter 62", "trabajo", "Revisar el sorter 62")
        ok("Guárdalo en trabajo: revisar el sorter 62", "trabajo", "Revisar el sorter 62")
        ok("anota en la universidad que tengo prueba de economía el lunes", "universidad", "Tengo prueba de economía el lunes")
        ok("anota para la u estudiar para el control", "universidad", "Estudiar para el control")
        ok("en personal comprar proteína", "personal", "Comprar proteína")
        ok("guarda en proyectos una idea app para el gym", "proyectos", "App para el gym")
        ok("guarda en la sección de proyectos dashboard de finanzas", "proyectos", "Dashboard de finanzas")
        ok("oye Jarvis guarda en trabajo revisar turnos", "trabajo", "Revisar turnos")
        ok("guarda en trabajo revisar el shorter 62 antes del cambio de turno", "trabajo", "Revisar el sorter 62 antes del cambio de turno")
    }

    @Test fun seccionAlFinal() {
        ok("anota comprar pan en personal", "personal", "Comprar pan")
        ok("revisar el informe de turno para el trabajo", "trabajo", "Revisar el informe de turno")
    }

    @Test fun sinSeccion() {
        ok("anota comprar proteína después del gym", null, "Comprar proteína después del gym")
        ok("recordar llamar al banco", null, "Recordar llamar al banco")
        ok("anota trabajo práctico de economía", null, "Trabajo práctico de economía")
        ok("el informe tiene que estar listo para el lunes", null, "El informe tiene que estar listo para el lunes")
    }
}
