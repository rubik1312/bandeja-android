package io.github.rubik1312.bandeja

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Base nativa de Bandeja: muestra la app web publicada en GitHub Pages y le agrega
 * lo que una página no puede hacer sola (voz nativa, abrirse como asistente, guardar archivos).
 * Los cambios de interfaz y funciones se publican en la web y llegan sin reinstalar.
 */
class MainActivity : Activity() {

    companion object {
        const val APP_URL = "https://rubik1312.github.io/bandeja/"
        const val APP_HOST = "rubik1312.github.io"
        const val RELEASES_URL = "https://api.github.com/repos/rubik1312/bandeja-android/releases/latest"
        const val REQ_MIC = 1
        const val REQ_ARCHIVO = 2
        const val REQ_JARVIS = 3
        const val REQ_CALENDARIO = 4

        /** La app abierta, para entregarle al instante las ideas que Jarvis guarda por voz. */
        var instancia: MainActivity? = null
            private set
    }

    private lateinit var web: WebView
    private var dictado: Dictado? = null
    private var vozPendiente = false
    private var archivoCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instancia = this
        web = WebView(this)
        // El WebView ignora su propio padding, así que el espacio para las barras va en un contenedor.
        val raiz = FrameLayout(this).apply { addView(web) }
        setContentView(raiz)

        // Android dibuja la app detrás de las barras del sistema; se deja espacio para ellas y el teclado.
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Puente(), "BandejaNativa")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == APP_HOST) return false
                // Los links externos se abren en el navegador, así el puente nativo solo lo ve Bandeja.
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                archivoCallback?.onReceiveValue(null)
                archivoCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_ARCHIVO)
                    true
                } catch (e: Exception) {
                    archivoCallback = null
                    false
                }
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(if (pideVoz(intent)) "$APP_URL?voz=1" else APP_URL)
        if (pideVoz(intent)) despertarPantalla()

        // Atrás primero cierra lo que esté abierto en la web (panel, Ajustes); si no hay nada, sale.
        // Desde Android 16 ya no llega onBackPressed, por eso se registra este aviso.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                web.evaluateJavascript("!!(window.bandejaAtras && window.bandejaAtras())") { cerrado ->
                    if (cerrado != "true") moveTaskToBack(true)
                }
            }
        }

        buscarActualizacion()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (pideVoz(intent)) {
            despertarPantalla()
            web.evaluateJavascript("window.bandejaIniciarVoz && window.bandejaIniciarVoz()", null)
        }
    }

    /** Se abrió desde "Dictar idea" (botón lateral), como asistente digital o con "Oye Jarvis". */
    private fun pideVoz(i: Intent?): Boolean =
        i?.action == Intent.ACTION_ASSIST || i?.action == JarvisService.ACCION_JARVIS ||
            i?.component?.className?.endsWith(".DictarIdea") == true

    /** Enciende la pantalla y pide desbloquear (huella o rostro) para no mostrar las ideas con el teléfono bloqueado. */
    private fun despertarPantalla() {
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }

    override fun onResume() {
        super.onResume()
        // Android corta la escucha al reiniciar el teléfono; abrir Bandeja la reactiva.
        if (JarvisService.prefs(this).getBoolean(JarvisService.PREF_ACTIVO, false) &&
            JarvisService.instancia == null && tienePermiso(Manifest.permission.RECORD_AUDIO)
        ) JarvisService.iniciar(this)
        pedirCalendario()
    }

    /** Se pide una sola vez, para que "recuérdame mañana…" pueda escribir en el calendario. */
    private fun pedirCalendario() {
        val p = JarvisService.prefs(this)
        if (!p.getBoolean(JarvisService.PREF_ACTIVO, false) || Calendario.tienePermiso(this) || p.getBoolean("calendario_pedido", false)) return
        p.edit().putBoolean("calendario_pedido", true).apply()
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQ_CALENDARIO)
    }

    override fun onStop() {
        super.onStop()
        dictado?.cancelar()
        JarvisService.instancia?.reanudar()
    }

    private fun tienePermiso(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- voz nativa ----------

    private fun iniciarVoz() {
        if (!tienePermiso(Manifest.permission.RECORD_AUDIO)) {
            vozPendiente = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val jarvis = JarvisService.instancia
        if (jarvis != null) {
            // Jarvis y el dictado no pueden usar el micrófono a la vez.
            jarvis.soltarMicrofono()
            web.postDelayed({ escuchar() }, 300)
        } else escuchar()
    }

    private fun escuchar() {
        dictado?.cancelar()
        dictado = Dictado(this, JarvisService.esperaMs(this), object : Dictado.Oyente {
            override fun listo() = avisarVoz("listo")
            override fun parcial(texto: String) = avisarVoz("parcial", texto)
            override fun final(texto: String) = avisarVoz("final", texto)
            override fun error(codigo: String) = avisarVoz("error", codigo)
        }).also { it.iniciar() }
    }

    /** Avisa a la web: window.bandejaVoz(evento, texto). Eventos: listo, parcial, final, error. */
    private fun avisarVoz(evento: String, texto: String = "") {
        web.evaluateJavascript(
            "window.bandejaVoz && window.bandejaVoz(${JSONObject.quote(evento)}, ${JSONObject.quote(texto)})",
            null
        )
    }

    /** Avisa a la web que hay ideas nuevas guardadas por voz (o ideas que deshacer). */
    fun entregarIdeas() = runOnUiThread {
        web.evaluateJavascript("window.bandejaNuevasIdeas && window.bandejaNuevasIdeas()", null)
    }

    // ---------- puente que ve la web como window.BandejaNativa ----------

    private inner class Puente {
        @JavascriptInterface
        fun disponible(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun iniciarVoz() = runOnUiThread { this@MainActivity.iniciarVoz() }

        @JavascriptInterface
        fun detenerVoz() = runOnUiThread { dictado?.detener() }

        @JavascriptInterface
        fun proximosEventos(): String = Calendario.proximos(this@MainActivity)

        @JavascriptInterface
        fun abrirEvento(id: Long): Boolean = Calendario.abrirEvento(this@MainActivity, id)

        @JavascriptInterface
        fun appsInstaladas(): String = Aplicaciones.instaladas(this@MainActivity)

        @JavascriptInterface
        fun appsSugeridas(): String = Aplicaciones.sugeridas(this@MainActivity)

        @JavascriptInterface
        fun iconoApp(paquete: String): String = Aplicaciones.icono(this@MainActivity, paquete)

        @JavascriptInterface
        fun abrirApp(paquete: String): Boolean = Aplicaciones.abrir(this@MainActivity, paquete)

        @JavascriptInterface
        fun tomarIdeas(): String = ColaIdeas.tomarIdeas(this@MainActivity)

        @JavascriptInterface
        fun tomarBorrados(): String = ColaIdeas.tomarBorrados(this@MainActivity)

        /** Opciones de Jarvis: modo (guardar/abrir), respuesta (notificacion/voz), espera (segundos de silencio). */
        @JavascriptInterface
        fun configurarJarvis(opcion: String, valor: String) = runOnUiThread {
            val e = JarvisService.prefs(this@MainActivity).edit()
            when (opcion) {
                "modo" -> if (valor in setOf("guardar", "abrir")) e.putString(JarvisService.PREF_MODO, valor)
                "respuesta" -> if (valor in setOf("notificacion", "voz")) e.putString(JarvisService.PREF_RESPUESTA, valor)
                "espera" -> valor.toIntOrNull()?.takeIf { it in 1..10 }?.let { e.putInt(JarvisService.PREF_ESPERA, it) }
            }
            e.apply()
            avisarJarvis()
        }

        @JavascriptInterface
        fun guardarArchivo(nombre: String, contenido: String) = runOnUiThread { guardarEnDescargas(nombre, contenido) }

        @JavascriptInterface
        fun version(): Long = versionCode()

        @JavascriptInterface
        fun jarvisEstado(): String = estadoJarvis().toString()

        @JavascriptInterface
        fun activarJarvis(@Suppress("UNUSED_PARAMETER") clave: String) = runOnUiThread {
            // El parámetro queda por compatibilidad con la web; openWakeWord no necesita clave.
            JarvisService.prefs(this@MainActivity).edit()
                .putBoolean(JarvisService.PREF_ACTIVO, true)
                .putBoolean(JarvisService.PREF_PAUSADO, false)
                .apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), REQ_JARVIS)
        }

        @JavascriptInterface
        fun desactivarJarvis() = runOnUiThread {
            JarvisService.prefs(this@MainActivity).edit().putBoolean(JarvisService.PREF_ACTIVO, false).apply()
            JarvisService.detener(this@MainActivity)
            web.postDelayed({ avisarJarvis() }, 300)
        }

        @JavascriptInterface
        fun pausarJarvis(pausar: Boolean) = runOnUiThread {
            val s = JarvisService.instancia
            if (s != null) s.cambiarPausa(pausar)
            else JarvisService.prefs(this@MainActivity).edit().putBoolean(JarvisService.PREF_PAUSADO, pausar).apply()
            avisarJarvis()
        }

        @JavascriptInterface
        fun sensibilidadJarvis(nivel: String) = runOnUiThread {
            if (nivel !in JarvisService.UMBRALES) return@runOnUiThread
            JarvisService.prefs(this@MainActivity).edit().putString(JarvisService.PREF_SENSIBILIDAD, nivel).apply()
            JarvisService.instancia?.aplicarSensibilidad()
            avisarJarvis()
        }

        @JavascriptInterface
        fun abrirPermisoEncima() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface
        fun abrirBateria() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    // ---------- Jarvis ----------

    private fun estadoJarvis() = JSONObject().apply {
        val p = JarvisService.prefs(this@MainActivity)
        put("activo", p.getBoolean(JarvisService.PREF_ACTIVO, false))
        put("escuchando", JarvisService.instancia != null)
        put("tieneClave", true)
        put("motor", "oww")
        put("pausado", p.getBoolean(JarvisService.PREF_PAUSADO, false))
        put("sensibilidad", p.getString(JarvisService.PREF_SENSIBILIDAD, "media"))
        put("modo", p.getString(JarvisService.PREF_MODO, "guardar"))
        put("respuesta", p.getString(JarvisService.PREF_RESPUESTA, "notificacion"))
        put("espera", p.getInt(JarvisService.PREF_ESPERA, 3))
        put("encima", Settings.canDrawOverlays(this@MainActivity))
        put("bateria", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        put("error", p.getString(JarvisService.PREF_ERROR, "") ?: "")
    }

    /** Avisa a la web: window.bandejaJarvis(estado). */
    private fun avisarJarvis() {
        web.evaluateJavascript("window.bandejaJarvis && window.bandejaJarvis(${estadoJarvis()})", null)
    }

    private fun guardarEnDescargas(nombre: String, contenido: String) {
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nombre)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IllegalStateException()
            contentResolver.openOutputStream(uri)!!.use { it.write(contenido.toByteArray()) }
            Toast.makeText(this, "Respaldo guardado en Descargas", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar el respaldo", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- actualización de la parte nativa ----------

    private fun versionCode(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode

    /** Revisa GitHub Releases (tag "v<versionCode>") y ofrece bajar el APK nuevo. */
    private fun buscarActualizacion() {
        Thread {
            try {
                val con = URL(RELEASES_URL).openConnection() as HttpURLConnection
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.connectTimeout = 8000
                con.readTimeout = 8000
                if (con.responseCode != 200) return@Thread
                val release = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                val nueva = release.optString("tag_name").removePrefix("v").toLongOrNull() ?: return@Thread
                if (nueva <= versionCode()) return@Thread
                val assets = release.optJSONArray("assets")
                var link = release.optString("html_url")
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) link = a.optString("browser_download_url")
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("Hay una versión nueva de Bandeja")
                        .setMessage("Descárgala e instálala encima. Tus ideas se mantienen.")
                        .setPositiveButton("Actualizar") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        .setNegativeButton("Después", null)
                        .show()
                }
            } catch (_: Exception) {
                // Sin señal o sin releases: se revisa de nuevo la próxima vez que se abra.
            }
        }.start()
    }

    // ---------- ciclo de vida ----------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_JARVIS) {
            if (tienePermiso(Manifest.permission.RECORD_AUDIO)) JarvisService.iniciar(this)
            else JarvisService.prefs(this).edit().putString(JarvisService.PREF_ERROR, "Falta permiso de micrófono").apply()
            // El detector tarda un momento en cargar los modelos; luego se informa el resultado a la web.
            web.postDelayed({ avisarJarvis() }, 1500)
            return
        }
        if (requestCode != REQ_MIC) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (ok && vozPendiente) iniciarVoz() else if (!ok) avisarVoz("error", "not-allowed")
        vozPendiente = false
    }

    @Deprecated("Selector de archivos del WebView")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ARCHIVO) {
            archivoCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            archivoCallback = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        dictado?.cancelar()
        if (instancia === this) instancia = null
        web.destroy()
        super.onDestroy()
    }
}
