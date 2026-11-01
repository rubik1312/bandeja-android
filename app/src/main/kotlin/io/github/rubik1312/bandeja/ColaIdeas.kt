package io.github.rubik1312.bandeja

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Ideas guardadas por voz con la app cerrada. Las ideas viven en la web (localStorage del WebView),
 * así que se dejan en esta cola y la web las toma al abrirse (o al instante si ya está abierta).
 */
object ColaIdeas {
    private const val IDEAS = "cola_ideas"
    private const val BORRAR = "cola_borrar"

    @Synchronized
    fun agregar(c: Context, texto: String, categoria: String?): String {
        val id = "voz" + System.currentTimeMillis().toString(36)
        val idea = JSONObject()
            .put("id", id)
            .put("texto", texto)
            .put("categoria", categoria ?: JSONObject.NULL)
            .put("creada", Instant.now().toString())
        guardar(c, IDEAS, leer(c, IDEAS).put(idea))
        return id
    }

    /** Si la web aún no la tomó, se saca de la cola; si ya la tomó, se le pide borrarla. */
    @Synchronized
    fun deshacer(c: Context, id: String) {
        val ideas = leer(c, IDEAS)
        val quedan = JSONArray()
        var estaba = false
        for (i in 0 until ideas.length()) {
            val o = ideas.getJSONObject(i)
            if (o.optString("id") == id) estaba = true else quedan.put(o)
        }
        if (estaba) guardar(c, IDEAS, quedan) else guardar(c, BORRAR, leer(c, BORRAR).put(id))
    }

    @Synchronized fun tomarIdeas(c: Context): String = tomar(c, IDEAS)
    @Synchronized fun tomarBorrados(c: Context): String = tomar(c, BORRAR)

    private fun tomar(c: Context, clave: String): String {
        val v = leer(c, clave).toString()
        guardar(c, clave, JSONArray())
        return v
    }

    private fun leer(c: Context, clave: String) =
        try { JSONArray(JarvisService.prefs(c).getString(clave, "[]")) } catch (_: Exception) { JSONArray() }

    private fun guardar(c: Context, clave: String, v: JSONArray) =
        JarvisService.prefs(c).edit().putString(clave, v.toString()).commit()
}
