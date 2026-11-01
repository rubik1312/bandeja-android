package io.github.rubik1312.bandeja

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Escucha "Jarvis" en segundo plano con openWakeWord (todo en el teléfono, sin internet ni cuentas)
 * y abre Bandeja en modo dictado. La notificación fija muestra si está escuchando o en pausa,
 * y desde ella (o el botón rápido del panel) se pausa sin apagar el servicio.
 */
class JarvisService : Service() {

    companion object {
        const val ACCION_JARVIS = "io.github.rubik1312.bandeja.JARVIS"
        private const val ACCION_PAUSAR = "io.github.rubik1312.bandeja.JARVIS_PAUSAR"
        private const val ACCION_REANUDAR = "io.github.rubik1312.bandeja.JARVIS_REANUDAR"
        // Canal nuevo con importancia baja: así el ícono se ve en la barra de estado (el anterior era mínima y no se veía).
        private const val CANAL_ESTADO = "jarvis_estado"
        private const val CANAL_AVISO = "jarvis_aviso"
        private const val ID_ESCUCHA = 1
        private const val ID_AVISO = 2

        const val PREF_ACTIVO = "jarvis_activo"
        const val PREF_PAUSADO = "jarvis_pausado"
        const val PREF_SENSIBILIDAD = "jarvis_sensibilidad"
        const val PREF_ERROR = "jarvis_error"

        /** Puntaje mínimo de openWakeWord para cada nivel de sensibilidad. */
        val UMBRALES = mapOf("alta" to 0.1f, "media" to 0.25f, "baja" to 0.5f)

        /** El servicio en marcha, para que la app y el botón rápido lo controlen. */
        var instancia: JarvisService? = null
            private set

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("bandeja", MODE_PRIVATE)
        fun pausado(c: Context) = prefs(c).getBoolean(PREF_PAUSADO, false)
        fun umbral(c: Context) = UMBRALES[prefs(c).getString(PREF_SENSIBILIDAD, "media")] ?: 0.25f

        fun iniciar(c: Context) = c.startForegroundService(Intent(c, JarvisService::class.java))
        fun detener(c: Context) = c.stopService(Intent(c, JarvisService::class.java))

        fun actualizarBotonRapido(c: Context) =
            TileService.requestListeningState(c, ComponentName(c, JarvisTile::class.java))
    }

    private var detector: DetectorJarvis? = null
    private var escuchando = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("jarvis")
        nm.createNotificationChannel(NotificationChannel(CANAL_ESTADO, "Estado de Jarvis", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISO, "Jarvis te escuchó", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(ID_ESCUCHA, notificacionEstado(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android no deja abrir el micrófono si la app no está a la vista; se reactiva al abrir Bandeja.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACCION_PAUSAR -> cambiarPausa(true)
            ACCION_REANUDAR -> cambiarPausa(false)
            else -> reanudar()
        }
        actualizarBotonRapido(this)
        return START_STICKY
    }

    /** Pausa o reanuda a pedido del usuario. En pausa no usa el micrófono ni gasta batería. */
    fun cambiarPausa(pausar: Boolean) {
        prefs(this).edit().putBoolean(PREF_PAUSADO, pausar).apply()
        if (pausar) soltarMicrofono() else reanudar()
        mostrarEstado()
        actualizarBotonRapido(this)
    }

    /** Empieza (o vuelve) a escuchar "Jarvis", salvo que el usuario lo haya pausado. */
    fun reanudar() {
        if (escuchando || pausado(this)) return mostrarEstado()
        try {
            val d = detector ?: DetectorJarvis(
                applicationContext,
                alOir = { main.post { escuchado() } },
                alFallar = { msg -> main.post { fallar(msg) } }
            ).also { detector = it }
            d.umbral = umbral(this)
            d.iniciar()
            escuchando = true
            prefs(this).edit().remove(PREF_ERROR).apply()
        } catch (e: Exception) {
            fallar("No pude cargar el detector: ${e.message ?: e.javaClass.simpleName}")
        }
        mostrarEstado()
    }

    /** Suelta el micrófono para que el dictado lo pueda usar (no cambia la pausa del usuario). */
    fun soltarMicrofono() {
        detector?.detener()
        escuchando = false
    }

    /** Aplica un cambio de sensibilidad sin reiniciar. */
    fun aplicarSensibilidad() {
        detector?.umbral = umbral(this)
    }

    private fun escuchado() {
        soltarMicrofono()
        val abrir = Intent(this, MainActivity::class.java)
            .setAction(ACCION_JARVIS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Settings.canDrawOverlays(this)) {
            startActivity(abrir)
        } else {
            // Sin el permiso "Aparecer encima", Android no deja abrir apps desde segundo plano: se avisa con una notificación.
            val pi = PendingIntent.getActivity(this, 1, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, CANAL_AVISO)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Te escuché")
                .setContentText("Toca para dictar tu idea")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ID_AVISO, n)
            // Si no se toca, vuelve a escuchar en unos segundos.
            main.postDelayed({ if (instancia === this) reanudar() }, 8000)
        }
    }

    private fun fallar(msg: String) {
        soltarMicrofono()
        prefs(this).edit().putString(PREF_ERROR, msg).apply()
        stopSelf()
    }

    private fun mostrarEstado() {
        getSystemService(NotificationManager::class.java).notify(ID_ESCUCHA, notificacionEstado())
    }

    private fun notificacionEstado(): Notification {
        val enPausa = pausado(this)
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val accion = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisService::class.java).setAction(if (enPausa) ACCION_REANUDAR else ACCION_PAUSAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icono = if (enPausa) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now
        return Notification.Builder(this, CANAL_ESTADO)
            .setSmallIcon(icono)
            .setContentTitle(if (enPausa) "Jarvis en pausa" else "Jarvis escuchando")
            .setContentText(if (enPausa) "No usa el micrófono. Reanúdalo cuando quieras." else "Di “Oye Jarvis” para dictar una idea")
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, icono), if (enPausa) "Reanudar" else "Pausar", accion).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        detector?.cerrar()
        detector = null
        escuchando = false
        instancia = null
        actualizarBotonRapido(this)
        super.onDestroy()
    }
}
