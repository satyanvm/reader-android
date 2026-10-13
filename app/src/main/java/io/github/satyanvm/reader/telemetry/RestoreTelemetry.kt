package io.github.satyanvm.reader.telemetry

import android.content.Context
import android.util.Log

/**
 * Restore outcomes, so unexpected resets are measurable. An [Event.UNEXPECTED_RESET] means
 * saved progress existed but the reader ended up at the first page anyway. Treat any
 * occurrence as a high-priority defect.
 */
interface RestoreTelemetry {
    fun record(event: Event, detail: String = "")

    enum class Event {
        /** Opened with no saved position: started at the beginning, as intended. */
        FIRST_OPEN,
        RESTORED_LOCAL,
        RESTORED_REMOTE,
        /** A newer position from another device arrived after opening and was applied. */
        REMOTE_APPLIED_LATE,
        /** The reader landed somewhere other than the restore target. */
        RESTORE_MISMATCH,
        UNEXPECTED_RESET,
        USER_RESTART,
    }
}

/** Logs every event and keeps lifetime counters on the device. */
class LocalRestoreTelemetry(context: Context) : RestoreTelemetry {
    private val prefs = context.getSharedPreferences("restore_telemetry", Context.MODE_PRIVATE)

    override fun record(event: RestoreTelemetry.Event, detail: String) {
        val priority = when (event) {
            RestoreTelemetry.Event.UNEXPECTED_RESET -> Log.ERROR
            RestoreTelemetry.Event.RESTORE_MISMATCH -> Log.WARN
            else -> Log.INFO
        }
        Log.println(priority, TAG, "${event.name} $detail".trim())
        prefs.edit().putLong(event.name, prefs.getLong(event.name, 0) + 1).apply()
    }

    fun counts(): Map<String, Long> =
        RestoreTelemetry.Event.entries.associate { it.name to prefs.getLong(it.name, 0) }

    private companion object {
        const val TAG = "ReaderRestore"
    }
}
