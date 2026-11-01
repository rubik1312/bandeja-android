package io.github.rubik1312.bandeja

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
    }

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var vozPendiente = false
    private var archivoCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        // Android dibuja la app detrás de las barras del sistema; se deja espacio para ellas y el teclado.
        web.setOnApplyWindowInsetsListener { v, insets ->
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
    }

    override fun onStop() {
        super.onStop()
        recognizer?.cancel()
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
            jarvis.pausar()
            web.postDelayed({ escuchar() }, 300)
        } else escuchar()
    }

    private fun escuchar() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(Oyente())
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    /** Avisa a la web: window.bandejaVoz(evento, texto). Eventos: listo, parcial, final, error. */
    private fun avisarVoz(evento: String, texto: String = "") {
        web.evaluateJavascript(
            "window.bandejaVoz && window.bandejaVoz(${JSONObject.quote(evento)}, ${JSONObject.quote(texto)})",
            null
        )
    }

    private inner class Oyente : RecognitionListener {
        private fun primero(b: Bundle?) =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        override fun onReadyForSpeech(params: Bundle?) = avisarVoz("listo")
        override fun onPartialResults(partialResults: Bundle?) = avisarVoz("parcial", primero(partialResults))
        override fun onResults(results: Bundle?) = avisarVoz("final", primero(results))
        override fun onError(error: Int) = avisarVoz(
            "error", when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                else -> "error-$error"
            }
        )
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- puente que ve la web como window.BandejaNativa ----------

    private inner class Puente {
        @JavascriptInterface
        fun disponible(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun iniciarVoz() = runOnUiThread { this@MainActivity.iniciarVoz() }

        @JavascriptInterface
        fun detenerVoz() = runOnUiThread { recognizer?.stopListening() }

        @JavascriptInterface
        fun guardarArchivo(nombre: String, contenido: String) = runOnUiThread { guardarEnDescargas(nombre, contenido) }

        @JavascriptInterface
        fun version(): Long = versionCode()

        @JavascriptInterface
        fun jarvisEstado(): String = estadoJarvis().toString()

        @JavascriptInterface
        fun activarJarvis(clave: String) = runOnUiThread {
            if (clave.isNotBlank()) JarvisService.prefs(this@MainActivity).edit()
                .putString(JarvisService.PREF_CLAVE, clave.trim()).apply()
            JarvisService.prefs(this@MainActivity).edit().putBoolean(JarvisService.PREF_ACTIVO, true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), REQ_JARVIS)
        }

        @JavascriptInterface
        fun desactivarJarvis() = runOnUiThread {
            JarvisService.prefs(this@MainActivity).edit().putBoolean(JarvisService.PREF_ACTIVO, false).apply()
            JarvisService.detener(this@MainActivity)
            web.postDelayed({ avisarJarvis() }, 300)
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
        put("tieneClave", !p.getString(JarvisService.PREF_CLAVE, "").isNullOrBlank())
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
            // Porcupine tarda un momento en validar la clave; luego se informa el resultado a la web.
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
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }
}
