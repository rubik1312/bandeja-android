package io.github.rubik1312.bandeja

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Escucha "Jarvis" en segundo plano con openWakeWord (todo en el teléfono, sin internet ni cuentas)
 * y abre Bandeja en modo dictado. Mientras corre, Android muestra una notificación fija.
 */
class JarvisService : Service() {

    companion object {
        const val ACCION_JARVIS = "io.github.rubik1312.bandeja.JARVIS"
        private const val CANAL_ESCUCHA = "jarvis"
        private const val CANAL_AVISO = "jarvis_aviso"
        private const val ID_ESCUCHA = 1
        private const val ID_AVISO = 2

        const val PREF_ACTIVO = "jarvis_activo"
        const val PREF_ERROR = "jarvis_error"

        /** El servicio en marcha, para que la app lo pause mientras usa el micrófono. */
        var instancia: JarvisService? = null
            private set

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("bandeja", MODE_PRIVATE)

        fun iniciar(c: Context) = c.startForegroundService(Intent(c, JarvisService::class.java))
        fun detener(c: Context) = c.stopService(Intent(c, JarvisService::class.java))
    }

    private var detector: DetectorJarvis? = null
    private var escuchando = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_ESCUCHA, "Escucha de Jarvis", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISO, "Jarvis te escuchó", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(ID_ESCUCHA, notificacionEscucha(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android no deja abrir el micrófono si la app no está a la vista; se reactiva al abrir Bandeja.
            stopSelf()
            return START_NOT_STICKY
        }
        reanudar()
        return START_STICKY
    }

    /** Empieza (o vuelve) a escuchar "Jarvis". */
    fun reanudar() {
        if (escuchando) return
        try {
            val d = detector ?: DetectorJarvis(
                applicationContext,
                alOir = { main.post { escuchado() } },
                alFallar = { msg -> main.post { fallar(msg) } }
            ).also { detector = it }
            d.iniciar()
            escuchando = true
            prefs(this).edit().remove(PREF_ERROR).apply()
        } catch (e: Exception) {
            fallar("No pude cargar el detector: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Suelta el micrófono para que el dictado lo pueda usar. */
    fun pausar() {
        detector?.detener()
        escuchando = false
    }

    private fun escuchado() {
        pausar()
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
        pausar()
        prefs(this).edit().putString(PREF_ERROR, msg).apply()
        stopSelf()
    }

    private fun notificacionEscucha(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL_ESCUCHA)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis está atento")
            .setContentText("Di “Oye Jarvis” para dictar una idea")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        detector?.cerrar()
        detector = null
        escuchando = false
        instancia = null
        super.onDestroy()
    }
}
