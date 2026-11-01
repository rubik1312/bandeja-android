package io.github.rubik1312.bandeja

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Botones de las notificaciones de Jarvis: deshacer lo guardado y detener el cronómetro. */
class AccionNotificacion : BroadcastReceiver() {
    companion object {
        const val DESHACER_IDEA = "io.github.rubik1312.bandeja.DESHACER_IDEA"
        const val DESHACER_EVENTO = "io.github.rubik1312.bandeja.DESHACER_EVENTO"
        const val DETENER_CRONOMETRO = "io.github.rubik1312.bandeja.DETENER_CRONOMETRO"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DESHACER_IDEA -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                ColaIdeas.deshacer(context, id)
                MainActivity.instancia?.entregarIdeas()
                JarvisService.quitarConfirmacion(context)
                Toast.makeText(context, "Idea borrada", Toast.LENGTH_SHORT).show()
            }
            DESHACER_EVENTO -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id < 0) return
                try {
                    Calendario.borrar(context, id)
                    Toast.makeText(context, "Recordatorio borrado", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "No pude borrar el recordatorio", Toast.LENGTH_LONG).show()
                }
                JarvisService.quitarConfirmacion(context)
            }
            DETENER_CRONOMETRO -> Cronometro.detener(context)?.let { Cronometro.mostrarResultado(context, it) }
        }
    }
}
