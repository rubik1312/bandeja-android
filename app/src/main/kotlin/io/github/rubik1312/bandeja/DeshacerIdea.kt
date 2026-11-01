package io.github.rubik1312.bandeja

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Botón "Deshacer" de la notificación "Guardada en…": borra la idea recién guardada por voz. */
class DeshacerIdea : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        ColaIdeas.deshacer(context, id)
        MainActivity.instancia?.entregarIdeas()
        JarvisService.quitarConfirmacion(context)
        Toast.makeText(context, "Idea borrada", Toast.LENGTH_SHORT).show()
    }
}
