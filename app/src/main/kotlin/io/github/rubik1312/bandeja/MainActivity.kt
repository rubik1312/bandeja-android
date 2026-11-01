package io.github.rubik1312.bandeja

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

        buscarActualizacion()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (pideVoz(intent)) web.evaluateJavascript("window.bandejaIniciarVoz && window.bandejaIniciarVoz()", null)
    }

    /** Se abrió desde "Dictar idea" (botón lateral) o como asistente digital. */
    private fun pideVoz(i: Intent?): Boolean =
        i?.action == Intent.ACTION_ASSIST || i?.component?.className?.endsWith(".DictarIdea") == true

    // ---------- voz nativa ----------

    private fun iniciarVoz() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            vozPendiente = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
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
