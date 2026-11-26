package de.meshcore.pocketmonitor.rx

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import de.meshcore.pocketmonitor.protocol.TraceMeasurement
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TracePreset
import de.meshcore.pocketmonitor.protocol.TraceRadio
import de.meshcore.pocketmonitor.protocol.TraceRoute
import de.meshcore.pocketmonitor.protocol.TraceRun
import de.meshcore.pocketmonitor.protocol.TraceSeries
import de.meshcore.pocketmonitor.protocol.TraceSeriesOptions
import de.meshcore.pocketmonitor.protocol.TraceSeriesStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class TraceHistory(private val helper: HistoryDatabase) {
    private val db get() = helper.writableDatabase
    private val presetRevision = MutableStateFlow(0L)
    private val seriesRevision = MutableStateFlow(0L)
    val presetChanges = presetRevision.asStateFlow()
    val seriesChanges = seriesRevision.asStateFlow()

    internal fun restored() {
        presetRevision.update { it + 1 }
        seriesRevision.update { it + 1 }
    }

    fun presets(companion: String): List<TracePreset> =
        db.rawQuery("SELECT * FROM trace_preset WHERE companion = ? ORDER BY name COLLATE NOCASE, id", arrayOf(companion))
            .rows { TracePreset(long("id"), text("name")!!, route()) }

    fun savePreset(companion: String, id: Long?, name: String, route: TraceRoute): Long {
        require(name.isNotBlank() && name.length <= 80 && route.path != null)
        val values = contentValuesOf("companion" to companion, "name" to name.trim()).apply { putRoute(route) }
        val result = if (id == null) db.insertOrThrow("trace_preset", null, values) else {
            check(db.update("trace_preset", values, "companion = ? AND id = ?", arrayOf(companion, id.toString())) == 1)
            id
        }
        presetRevision.update { it + 1 }
        return result
    }

    fun deletePreset(companion: String, id: Long) {
        db.delete("trace_preset", "companion = ? AND id = ?", arrayOf(companion, id.toString()))
        presetRevision.update { it + 1 }
    }

    fun series(companion: String, beforeId: Long, limit: Int): List<TraceSeries> =
        db.rawQuery(
            "SELECT * FROM trace_series WHERE companion = ? AND id < ? ORDER BY id DESC LIMIT ?",
            arrayOf(companion, beforeId.toString(), limit.toString()),
        ).rows {
            TraceSeries(
                long("id"), companion, text("name"), route(),
                TraceSeriesOptions(int("requested"), int("interval_seconds"), int("timeout_seconds")),
                long("started_at"), TraceRadio(
                    nullableLong("freq_khz"), nullableLong("bw_hz"), nullableInt("sf"), nullableInt("cr"),
                    nullableInt("power_dbm"), text("firmware"), nullableInt("protocol"), text("radio_ic"),
                ), TraceSeriesStatus.valueOf(text("status")!!), text("message"),
            )
        }

    fun measurements(series: Long): List<TraceMeasurement> = db.rawQuery(
        "SELECT * FROM trace_measurement WHERE series_id = ? ORDER BY number", arrayOf(series.toString()),
    ).rows {
        TraceMeasurement(
            int("number"), long("started_at"), TracePhase.valueOf(text("phase")!!), int("accepted") != 0,
            nullableLong("response_ms"), text("snr")?.split(',')?.map(String::toDouble).orEmpty(), text("message"),
        )
    }

    fun beginSeries(
        companion: String, name: String?, route: TraceRoute, options: TraceSeriesOptions, startedAt: Long, radio: TraceRadio,
    ): Long {
        val id = db.insertOrThrow("trace_series", null, contentValuesOf(
            "companion" to companion, "name" to name, "requested" to options.count,
            "interval_seconds" to options.intervalSeconds, "timeout_seconds" to options.timeoutSeconds,
            "started_at" to startedAt, "status" to TraceSeriesStatus.RUNNING.name,
        ).apply {
            putRoute(route)
            putRadio(radio)
        })
        seriesRevision.update { it + 1 }
        return id
    }

    fun saveRadio(series: Long, radio: TraceRadio) {
        db.update("trace_series", ContentValues().apply { putRadio(radio) }, "id = ?", arrayOf(series.toString()))
        seriesRevision.update { it + 1 }
    }

    fun beginMeasurement(series: Long, number: Int, run: TraceRun) {
        db.insertOrThrow("trace_measurement", null, contentValuesOf(
            "series_id" to series, "number" to number, "started_at" to run.startedAtMs,
            "phase" to TracePhase.CONFIRMING.name, "tag" to run.tag.toLong(), "auth" to run.auth.toLong(),
        ))
        seriesRevision.update { it + 1 }
    }

    fun saveMeasurement(series: Long, number: Int, run: TraceRun) {
        db.update("trace_measurement", contentValuesOf(
            "phase" to run.phase.name, "accepted" to if (run.accepted) 1 else 0,
            "response_ms" to run.responseMs,
            "snr" to run.response?.let { (it.hopSnrDb + it.finalSnrDb).joinToString(",") },
            "message" to run.message, "started_at" to run.startedAtMs,
        ), "series_id = ? AND number = ?", arrayOf(series.toString(), number.toString()))
        seriesRevision.update { it + 1 }
    }

    fun finishSeries(id: Long, status: TraceSeriesStatus, message: String?) {
        db.update("trace_series", contentValuesOf("status" to status.name, "message" to message),
            "id = ?", arrayOf(id.toString()))
        seriesRevision.update { it + 1 }
    }

    companion object {
        fun interruptRunning(db: SQLiteDatabase) = db.inTransaction {
            val message = "Durch Prozessende unterbrochen."
            db.execSQL("UPDATE trace_measurement SET phase = 'CANCELLED', message = ? WHERE phase IN ('CONFIRMING', 'LISTENING')",
                arrayOf(message))
            db.execSQL("UPDATE trace_series SET status = 'ABORTED', message = ? WHERE status = 'RUNNING'", arrayOf(message))
        }
    }
}

private fun ContentValues.putRadio(radio: TraceRadio) {
    put("freq_khz", radio.frequencyKhz)
    put("bw_hz", radio.bandwidthHz)
    put("sf", radio.sf)
    put("cr", radio.cr)
    put("power_dbm", radio.powerDbm)
    put("firmware", radio.firmware)
    put("protocol", radio.protocol)
    put("radio_ic", radio.radioIc)
}

private fun ContentValues.putRoute(route: TraceRoute) {
    put("width", route.width)
    put("entries", route.entries.joinToString(","))
    put("mirror", if (route.mirror) 1 else 0)
    put("turn", route.turn)
}

private fun Cursor.route() = TraceRoute(int("width"), text("entries")!!.split(','), int("mirror") != 0, nullableInt("turn"))
private fun Cursor.text(name: String): String? = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.nullableInt(name: String): Int? = nullableLong(name)?.toInt()
private inline fun <T> Cursor.rows(read: Cursor.() -> T): List<T> = use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.read()) }
}
