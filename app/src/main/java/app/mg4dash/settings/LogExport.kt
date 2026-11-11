package app.mg4dash.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log written to the USB stick (#36, Settings › System › Log): a text file to attach to an issue, the
 * Compatibility report at the top, then the whole log buffer (MG4Dash's and Radio Dash's lines), oldest first.
 */
object LogExport {
    /** mg4dash_log_YYYYMMDD_HHMM.txt */
    fun fileName(now: Long): String = "mg4dash_log_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(now)) + ".txt"

    /** [lines] as shown, newest first: written oldest first, after the [header] and the [report]. */
    fun text(header: String, report: String, lines: List<String>): String = buildString {
        append(header).append("\n\n")
        append(report.trimEnd()).append("\n\n")
        append("── Log ──\n")
        lines.asReversed().forEach { append(it).append('\n') }
    }
}
