package io.github.rubik1312.bandeja

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.FloatBuffer
import java.util.Random

/**
 * Detecta "Hey/Oye Jarvis" con los modelos de openWakeWord (assets/oww), todo en el teléfono.
 * Cada 80 ms de audio pasa por tres modelos: espectrograma → embedding → puntaje del modelo "hey jarvis".
 */
class DetectorJarvis(
    private val context: Context,
    @Volatile var umbral: Float = 0.25f,
    private val alOir: () -> Unit,
    private val alFallar: (String) -> Unit
) {
    private companion object {
        const val TASA = 16000
        const val TROZO = 1280          // 80 ms
        const val CONTEXTO = 480        // muestras extra que necesita el espectrograma
        const val FRAMES_MEL = 76
        const val BANDAS = 32
        const val EMBEDDINGS = 16
        const val DIM = 96
        const val ENFRIAMIENTO = 25     // ~2 s sin volver a disparar
        const val SILENCIO_RMS = 100f   // bajo esto (PCM 16 bits) el trozo se trata como silencio
    }

    private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }

    // Modelos chicos: un solo hilo y sin espera activa, si no ONNX Runtime ocupa todos los núcleos y gasta batería.
    private fun sesion(nombre: String): OrtSession {
        val opciones = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
            // XNNPACK acelera las convoluciones en procesadores ARM; si no está, sigue con la CPU normal.
            try { addXnnpack(mapOf("intra_op_num_threads" to "1")) } catch (_: Exception) {}
        }
        return env.createSession(context.assets.open("oww/$nombre").use { it.readBytes() }, opciones)
    }

    private val mel = sesion("melspectrogram.onnx")
    private val emb = sesion("embedding_model.onnx")
    private val ww = sesion("hey_jarvis_v0.1.onnx")

    private val crudo = FloatArray(TROZO + CONTEXTO)
    private val frames = ArrayDeque<FloatArray>().apply { repeat(FRAMES_MEL) { addLast(FloatArray(BANDAS) { 1f }) } }
    private val embeddings = ArrayDeque<FloatArray>()
    private var enfriamiento = 0

    // En silencio los modelos siempre dan lo mismo: se calcula una vez y se reutiliza, para no gastar batería.
    private val melSilencio: List<FloatArray>
    private val embSilencio: FloatArray
    private var framesSilencio = 0

    @Volatile private var activo = false
    private var hilo: Thread? = null

    init {
        melSilencio = calcularMel(FloatArray(TROZO + CONTEXTO))
        embSilencio = calcularEmbedding(List(FRAMES_MEL) { melSilencio.last() })
        // Igual que openWakeWord: se llena el historial con ruido suave antes de escuchar de verdad.
        val r = Random(0)
        val ruido = FloatArray(TROZO)
        repeat(TASA * 4 / TROZO) {
            for (i in ruido.indices) ruido[i] = (r.nextInt(2000) - 1000).toFloat()
            puntaje(ruido)
        }
    }

    fun iniciar() {
        if (activo) return
        activo = true
        hilo = Thread(::escuchar, "jarvis").apply { start() }
    }

    fun detener() {
        activo = false
        hilo?.join(1000)
        hilo = null
    }

    fun cerrar() {
        detener()
        mel.close(); emb.close(); ww.close()
    }

    @SuppressLint("MissingPermission") // el servicio solo arranca con el permiso de micrófono concedido
    private fun escuchar() {
        val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, TROZO * 4)
            ).also { it.startRecording() }
        } catch (e: Exception) {
            activo = false
            return alFallar("No pude abrir el micrófono")
        }
        val pcm = ShortArray(TROZO)
        val audio = FloatArray(TROZO)
        try {
            while (activo) {
                var leidas = 0
                while (leidas < TROZO && activo) {
                    val n = rec.read(pcm, leidas, TROZO - leidas)
                    if (n < 0) { activo = false; alFallar("El micrófono dejó de responder"); break }
                    leidas += n
                }
                if (leidas < TROZO) continue
                for (i in 0 until TROZO) audio[i] = pcm[i].toFloat()
                val p = puntaje(audio)
                diagnostico(p)
                if (p >= 0.02f) Log.d("Jarvis", "pico=%.3f rms=%.0f umbral=%.2f".format(p, ultimoRms, umbral))
                if (enfriamiento > 0) enfriamiento--
                else if (p >= umbral) { enfriamiento = ENFRIAMIENTO; alOir() }
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    // Resumen cada ~2 s en logcat (adb logcat -s Jarvis) para calibrar umbrales con el micrófono real.
    private var ultimoRms = 0f
    private var maxRms = 0f
    private var maxPuntaje = 0f
    private var trozosDiag = 0
    private fun diagnostico(p: Float) {
        maxRms = maxOf(maxRms, ultimoRms)
        maxPuntaje = maxOf(maxPuntaje, p)
        if (++trozosDiag < 25) return
        Log.d("Jarvis", "rms max=%.0f puntaje max=%.3f".format(maxRms, maxPuntaje))
        trozosDiag = 0; maxRms = 0f; maxPuntaje = 0f
    }

    /** Procesa 80 ms de audio (muestras PCM de 16 bits como float) y devuelve el puntaje 0..1. */
    private fun puntaje(trozo: FloatArray): Float {
        var energia = 0f
        for (v in trozo) energia += v * v
        ultimoRms = Math.sqrt((energia / trozo.size).toDouble()).toFloat()
        val silencio = ultimoRms < SILENCIO_RMS

        if (silencio) {
            crudo.fill(0f)
            frames.addAll(melSilencio)
            framesSilencio += melSilencio.size
        } else {
            System.arraycopy(crudo, TROZO, crudo, 0, CONTEXTO)
            System.arraycopy(trozo, 0, crudo, CONTEXTO, TROZO)
            frames.addAll(calcularMel(crudo))
            framesSilencio = 0
        }
        while (frames.size > FRAMES_MEL) frames.removeFirst()

        val todoSilencio = silencio && framesSilencio >= FRAMES_MEL
        embeddings.addLast(if (todoSilencio) embSilencio else calcularEmbedding(frames))
        while (embeddings.size > EMBEDDINGS) embeddings.removeFirst()
        if (embeddings.size < EMBEDDINGS) return 0f
        if (todoSilencio && embeddings.all { it === embSilencio }) return 0f

        val x = FloatArray(EMBEDDINGS * DIM)
        embeddings.forEachIndexed { i, e -> System.arraycopy(e, 0, x, i * DIM, DIM) }
        return correr(ww, ww.inputNames.first(), x, longArrayOf(1, EMBEDDINGS.toLong(), DIM.toLong()))[0]
    }

    private fun calcularMel(audio: FloatArray): List<FloatArray> {
        val m = correr(mel, "input", audio, longArrayOf(1, audio.size.toLong()))
        return List(m.size / BANDAS) { f -> FloatArray(BANDAS) { m[f * BANDAS + it] / 10f + 2f } }
    }

    private fun calcularEmbedding(ventana: Collection<FloatArray>): FloatArray {
        val x = FloatArray(FRAMES_MEL * BANDAS)
        ventana.forEachIndexed { i, fr -> System.arraycopy(fr, 0, x, i * BANDAS, BANDAS) }
        return correr(emb, "input_1", x, longArrayOf(1, FRAMES_MEL.toLong(), BANDAS.toLong(), 1))
    }

    private fun correr(s: OrtSession, entrada: String, datos: FloatArray, forma: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(datos), forma).use { t ->
            s.run(mapOf(entrada to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
}
