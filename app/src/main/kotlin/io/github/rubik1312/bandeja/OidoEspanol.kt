package io.github.rubik1312.bandeja

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Segundo detector: reconoce voz en español con Vosk (modelo en assets/vosk-es) y avisa si oye "jarvis".
 * Complementa a openWakeWord, que solo reconoce bien la pronunciación en inglés ("yarvis").
 * Para no gastar batería, solo procesa audio mientras hay voz (más un poco antes y después).
 */
class OidoEspanol(context: Context) : AutoCloseable {

    private companion object {
        const val VERSION_MODELO = "vosk-model-small-es-0.42"
        const val PREVIO = 3      // trozos de 80 ms guardados para no cortar el inicio de la frase
        const val COLA = 8        // trozos que se siguen procesando tras la voz (~0,6 s)
        val JARVIS = Regex("\\bjarvis\\b")
    }

    private val modelo: Model
    private val rec: Recognizer
    private val previos = ArrayDeque<ShortArray>()
    private var cola = 0
    private var alimentado = false

    init {
        modelo = Model(prepararModelo(context).absolutePath)
        rec = Recognizer(modelo, 16000f)
    }

    /** Vosk necesita el modelo como carpeta: se copia una vez desde assets a los archivos de la app. */
    private fun prepararModelo(c: Context): File {
        val destino = File(c.filesDir, "vosk-es")
        val marca = File(destino, ".version")
        if (marca.exists() && marca.readText() == VERSION_MODELO) return destino
        destino.deleteRecursively()
        fun copiar(ruta: String) {
            val hijos = c.assets.list(ruta).orEmpty()
            if (hijos.isEmpty()) {
                val f = File(c.filesDir, ruta).apply { parentFile?.mkdirs() }
                c.assets.open(ruta).use { entrada -> f.outputStream().use { entrada.copyTo(it) } }
            } else hijos.forEach { copiar("$ruta/$it") }
        }
        copiar("vosk-es")
        marca.writeText(VERSION_MODELO)
        return destino
    }

    /** Recibe 80 ms de audio; devuelve true si en lo que va de la frase se escuchó "jarvis". */
    fun escuchar(pcm: ShortArray, hayVoz: Boolean): Boolean {
        if (hayVoz) cola = COLA else if (cola > 0) cola-- else {
            guardarPrevio(pcm)
            if (alimentado) { rec.reset(); alimentado = false }
            return false
        }
        if (!alimentado) {
            // Arranca una frase nueva: primero el audio de justo antes, para no perder la primera sílaba.
            for (p in previos) rec.acceptWaveForm(p, p.size)
            previos.clear()
            alimentado = true
        }
        val fin = rec.acceptWaveForm(pcm, pcm.size)
        val texto = if (fin) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
        if (JARVIS.containsMatchIn(texto)) {
            rec.reset()
            alimentado = false
            return true
        }
        return false
    }

    private fun guardarPrevio(pcm: ShortArray) {
        previos.addLast(pcm.copyOf())
        while (previos.size > PREVIO) previos.removeFirst()
    }

    override fun close() {
        rec.close()
        modelo.close()
    }
}
