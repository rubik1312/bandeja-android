package io.github.rubik1312.bandeja

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Crea recordatorios en el calendario del teléfono, con aviso a la hora del evento. */
object Calendario {
    private const val DURACION_MS = 30 * 60 * 1000L

    fun tienePermiso(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Devuelve el id del evento, o null si no hay un calendario donde escribir. */
    fun crear(c: Context, titulo: String, inicio: LocalDateTime): Long? {
        val calendario = elegirCalendario(c) ?: return null
        val desde = inicio.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val evento = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendario)
            put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.DTSTART, desde)
            put(CalendarContract.Events.DTEND, desde + DURACION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, evento) ?: return null
        val id = ContentUris.parseId(uri)
        c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, 0)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return id
    }

    fun borrar(c: Context, id: Long) {
        c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    /** Prefiere el calendario principal de la cuenta de Google. */
    private fun elegirCalendario(c: Context): Long? {
        val columnas = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val filtro = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var mejor: Long? = null
        var puntaje = -1
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columnas, filtro, null, null)?.use { f ->
            while (f.moveToNext()) {
                val p = (if (f.getInt(1) == 1) 2 else 0) + (if (f.getString(2) == "com.google") 1 else 0)
                if (p > puntaje) { puntaje = p; mejor = f.getLong(0) }
            }
        }
        return mejor
    }
}
