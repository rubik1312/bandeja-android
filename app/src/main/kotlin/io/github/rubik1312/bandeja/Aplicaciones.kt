package io.github.rubik1312.bandeja

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Lista y abre las apps del teléfono para el panel lateral de Bandeja. */
object Aplicaciones {
    private const val TAMANO_ICONO = 96

    /** Todas las apps que se pueden abrir: [{paquete, nombre}], por nombre. */
    fun instaladas(c: Context): String {
        val pm = c.packageManager
        val lista = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != c.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        return JSONArray(lista.map { (p, n) -> JSONObject().put("paquete", p).put("nombre", n) }).toString()
    }

    /** Las apps de uso diario de este teléfono, sean de Samsung, Google u otra marca. */
    fun sugeridas(c: Context): String {
        fun categoria(cat: String) = Intent(Intent.ACTION_MAIN).addCategory(cat)
        // Cada rol, con paquetes conocidos para las apps que no se declaran como tales (como las de Samsung).
        val roles = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS) to emptyList(),
            categoria(Intent.CATEGORY_APP_CALENDAR) to emptyList(),
            categoria(Intent.CATEGORY_APP_CALCULATOR) to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            categoria(Intent.CATEGORY_APP_EMAIL) to emptyList(),
            categoria(Intent.CATEGORY_APP_FILES) to listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to emptyList(),
        )
        val pm = c.packageManager
        // Si hay varias opciones sin predeterminada, Android responde con su selector: no es una app para mostrar.
        fun abrible(p: String?) = p != null && pm.getLaunchIntentForPackage(p) != null
        val paquetes = roles.mapNotNull { (rol, conocidos) ->
            pm.resolveActivity(rol, 0)?.activityInfo?.packageName?.takeIf(::abrible) ?: conocidos.firstOrNull(::abrible)
        }.distinct()
        return JSONArray(paquetes).toString()
    }

    /** Ícono como data URL, para mostrarlo tal cual en la web. */
    fun icono(c: Context, paquete: String): String = try {
        val d = c.packageManager.getApplicationIcon(paquete)
        val bmp = Bitmap.createBitmap(TAMANO_ICONO, TAMANO_ICONO, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, TAMANO_ICONO, TAMANO_ICONO)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }

    fun abrir(c: Context, paquete: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(paquete) ?: return false
        c.startActivity(i)
        return true
    }
}
