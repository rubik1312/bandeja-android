package io.github.rubik1312.bandeja

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * Cronómetro propio, porque Android no tiene una forma estándar de manejar el de la app Reloj.
 * Corre en la notificación y guarda la hora de partida, así no gasta batería mientras avanza.
 */
object Cronometro {
    private const val CANAL = "jarvis_cronometro"
    private const val ID = 4
    private const val PREF_INICIO = "cronometro_inicio"

    fun enCurso(c: Context) = JarvisService.prefs(c).getLong(PREF_INICIO, 0L) > 0L

    fun iniciar(c: Context) {
        val inicio = System.currentTimeMillis()
        JarvisService.prefs(c).edit().putLong(PREF_INICIO, inicio).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Cronómetro", NotificationManager.IMPORTANCE_LOW))
        val detener = PendingIntent.getBroadcast(
            c, ID, Intent(c, AccionNotificacion::class.java).setAction(AccionNotificacion.DETENER_CRONOMETRO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(ID, Notification.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Cronómetro")
            .setWhen(inicio)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_media_pause), "Detener", detener).build())
            .build())
    }

    /** Detiene y devuelve el tiempo en palabras, o null si no estaba corriendo. */
    fun detener(c: Context): String? {
        val inicio = JarvisService.prefs(c).getLong(PREF_INICIO, 0L)
        if (inicio <= 0L) return null
        JarvisService.prefs(c).edit().remove(PREF_INICIO).apply()
        c.getSystemService(NotificationManager::class.java).cancel(ID)
        return duracion((System.currentTimeMillis() - inicio) / 1000)
    }

    /** Para el botón Detener: muestra el resultado en la misma notificación. */
    fun mostrarResultado(c: Context, tiempo: String) {
        c.getSystemService(NotificationManager::class.java).notify(ID, Notification.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Cronómetro detenido")
            .setContentText(tiempo)
            .build())
    }

    fun duracion(segundos: Long): String {
        val h = segundos / 3600
        val m = segundos % 3600 / 60
        val s = segundos % 60
        return listOfNotNull(
            if (h > 0) "$h ${if (h == 1L) "hora" else "horas"}" else null,
            if (m > 0) "$m ${if (m == 1L) "minuto" else "minutos"}" else null,
            if (s > 0 || (h == 0L && m == 0L)) "$s ${if (s == 1L) "segundo" else "segundos"}" else null,
        ).joinToString(" ")
    }
}
