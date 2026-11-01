package io.github.rubik1312.bandeja

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Botón "Jarvis" del panel rápido: pausa o reanuda la escucha con un toque. */
class JarvisTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        pintar()
    }

    override fun onClick() {
        super.onClick()
        val servicio = JarvisService.instancia
        if (servicio != null) {
            servicio.cambiarPausa(!JarvisService.pausado(this))
        } else {
            // Apagado (por ejemplo tras reiniciar): Android solo deja encender el micrófono con la app a la vista.
            JarvisService.prefs(this).edit()
                .putBoolean(JarvisService.PREF_ACTIVO, true)
                .putBoolean(JarvisService.PREF_PAUSADO, false)
                .apply()
            val abrir = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE))
        }
        pintar()
    }

    private fun pintar() {
        val tile = qsTile ?: return
        val corriendo = JarvisService.instancia != null
        val pausado = JarvisService.pausado(this)
        tile.state = if (corriendo && !pausado) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Jarvis"
        tile.subtitle = when {
            !corriendo -> "Apagado"
            pausado -> "En pausa"
            else -> "Escuchando"
        }
        tile.updateTile()
    }
}
