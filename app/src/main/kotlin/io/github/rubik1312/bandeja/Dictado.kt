package io.github.rubik1312.bandeja

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dictado que no se corta con una pausa corta. El reconocedor de Android termina tras ~1 s de silencio,
 * así que se encadenan sesiones y se junta el texto hasta que pasen [esperaMs] sin voz,
 * o hasta que se diga "listo"/"guardar", o se llame a [detener]. Debe usarse desde el hilo principal.
 */
class Dictado(private val ctx: Context, private val esperaMs: Long, private val oyente: Oyente) {

    interface Oyente {
        fun listo()
        fun parcial(texto: String)
        fun final(texto: String)
        fun error(codigo: String)
    }

    private companion object {
        // Frases para terminar antes de tiempo. No incluye "listo" ni "guardar" solos porque pueden ser parte de la idea.
        val PALABRA_FINAL = Regex("[\\s,.]*\\b(eso es todo|gu[aá]rdal[ao]|listo jarvis)[\\s.!]*$", RegexOption.IGNORE_CASE)
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private val acumulado = StringBuilder()
    private var inicio = 0L
    private var ultimaVoz = 0L
    private var terminar = false
    private var activo = false
    private var avisoListo = false
    private var reintentos = 0
    private var segmentado = false

    val enCurso get() = activo

    fun iniciar() {
        activo = true; terminar = false; avisoListo = false; reintentos = 0; segmentado = false
        acumulado.clear()
        inicio = SystemClock.elapsedRealtime(); ultimaVoz = inicio
        sesion()
    }

    /** Termina ya con lo dictado hasta ahora. */
    fun detener() {
        if (!activo) return
        terminar = true
        rec?.stopListening()
    }

    fun cancelar() {
        activo = false
        main.removeCallbacksAndMessages(null)
        rec?.destroy(); rec = null
    }

    private fun sesion() {
        Log.d("Dictado", "nueva sesión (llevo ${acumulado.length} letras)")
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(Escucha())
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // El reconocedor de Google lee estos tiempos como Int; si otro los ignora, el encadenado de sesiones lo cubre.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, esperaMs.toInt())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, esperaMs.toInt())
            })
        }
    }

    private fun texto(extra: String = "") =
        listOf(acumulado.toString().trim(), extra.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private fun finDeSesion() {
        if (!activo) return
        val ahora = SystemClock.elapsedRealtime()
        val t = texto()
        val sinVozHace = ahora - ultimaVoz
        if (terminar || PALABRA_FINAL.containsMatchIn(t) || sinVozHace >= esperaMs) terminarCon(t)
        else main.post { if (activo) sesion() }
    }

    private fun terminarCon(t: String) {
        activo = false
        main.removeCallbacksAndMessages(null)
        rec?.destroy(); rec = null
        val limpio = t.replace(PALABRA_FINAL, "").trim()
        Log.d("Dictado", "terminado: ${limpio.length} letras")
        if (limpio.isEmpty()) oyente.error("no-speech") else oyente.final(limpio)
    }

    private inner class Escucha : RecognitionListener {
        private fun primero(b: Bundle?) =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        override fun onReadyForSpeech(params: Bundle?) {
            reintentos = 0
            if (!avisoListo) { avisoListo = true; oyente.listo() }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = primero(partialResults)
            if (p.isNotBlank()) ultimaVoz = SystemClock.elapsedRealtime()
            // El reconocedor de Google, al dictar por segmentos, marca cada frase terminada con "final_result"
            // y luego empieza la siguiente desde cero: hay que ir juntándolas.
            if (partialResults?.containsKey("final_result") == true) {
                segmentado = true
                if (p.isNotBlank()) acumulado.append(' ').append(p)
                Log.d("Dictado", "frase terminada: ${p.length} letras")
                oyente.parcial(texto())
                if (PALABRA_FINAL.containsMatchIn(texto())) terminarCon(texto())
            } else oyente.parcial(texto(p))
        }

        override fun onResults(results: Bundle?) {
            val r = primero(results)
            Log.d("Dictado", "resultado de sesión: ${r.length} letras (segmentos=$segmentado) claves=${results?.keySet()}")
            if (r.isNotBlank()) {
                acumulado.append(' ').append(r)
                ultimaVoz = SystemClock.elapsedRealtime()
                oyente.parcial(texto())
            }
            // En modo por segmentos el reconocedor ya esperó el silencio pedido: se termina aquí.
            if (segmentado) terminarCon(texto()) else finDeSesion()
        }

        // Con los tiempos de silencio, el reconocedor de Google dicta "por segmentos": entrega cada frase
        // por aquí y sigue escuchando a través de las pausas, hasta [esperaMs] de silencio.
        override fun onSegmentResults(segmentResults: Bundle) {
            segmentado = true
            val s = primero(segmentResults)
            Log.d("Dictado", "segmento: ${s.length} letras")
            if (s.isNotBlank()) {
                acumulado.append(' ').append(s)
                ultimaVoz = SystemClock.elapsedRealtime()
                oyente.parcial(texto())
                if (PALABRA_FINAL.containsMatchIn(texto())) terminarCon(texto())
            }
        }

        override fun onEndOfSegmentedSession() {
            Log.d("Dictado", "fin del dictado por segmentos")
            if (activo) terminarCon(texto())
        }

        override fun onError(error: Int) {
            Log.d("Dictado", "error del reconocedor: $error (activo=$activo, reintentos=$reintentos)")
            if (!activo) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finDeSesion()
                // Al reiniciar muy rápido el reconocedor puede estar ocupado: se reintenta un par de veces.
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    if (reintentos++ < 3) main.postDelayed({ if (activo) sesion() }, 250) else terminarCon(texto())
                else -> {
                    val t = texto()
                    if (t.isNotEmpty()) terminarCon(t) else {
                        activo = false
                        rec?.destroy(); rec = null
                        oyente.error(
                            when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                                else -> "error-$error"
                            }
                        )
                    }
                }
            }
        }

        override fun onBeginningOfSpeech() { ultimaVoz = SystemClock.elapsedRealtime() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
