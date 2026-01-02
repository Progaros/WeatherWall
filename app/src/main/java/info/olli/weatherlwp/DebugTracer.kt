package info.olli.weatherlwp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lightweight debug tracing utility.
 * When enabled, collects timing data for function calls.
 * When disabled, copies collected trace to clipboard.
 */
object DebugTracer {
    
    private const val MAX_ENTRIES = 500
    
    @Volatile
    private var enabled = false
    
    private val entries = Collections.synchronizedList(mutableListOf<TraceEntry>())
    private val startTimes = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private var sessionStartTime = 0L
    
    data class TraceEntry(
        val timestamp: Long,
        val label: String,
        val durationNanos: Long?,
        val message: String?
    )
    
    fun isEnabled() = enabled
    
    /**
     * Enable tracing. Clears previous data.
     */
    fun enable() {
        entries.clear()
        startTimes.clear()
        sessionStartTime = System.currentTimeMillis()
        enabled = true
        log("Tracing", "Session started")
    }
    
    /**
     * Disable tracing and return the report.
     */
    fun disable(): String {
        enabled = false
        log("Tracing", "Session ended")
        return generateReport()
    }
    
    /**
     * Disable tracing and copy report to clipboard.
     */
    fun disableAndCopy(context: Context) {
        val report = disable()
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debug Trace", report)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Debug log copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Start timing a labeled operation.
     */
    fun traceStart(label: String) {
        if (!enabled) return
        startTimes[label] = System.nanoTime()
    }
    
    /**
     * End timing a labeled operation.
     */
    fun traceEnd(label: String) {
        if (!enabled) return
        val startTime = startTimes.remove(label) ?: return
        val durationNanos = System.nanoTime() - startTime
        addEntry(TraceEntry(System.currentTimeMillis(), label, durationNanos, null))
    }
    
    /**
     * Log a message without timing.
     */
    fun log(label: String, message: String) {
        if (!enabled) return
        addEntry(TraceEntry(System.currentTimeMillis(), label, null, message))
    }
    
    private fun addEntry(entry: TraceEntry) {
        if (entries.size >= MAX_ENTRIES) {
            entries.removeAt(0)
        }
        entries.add(entry)
    }
    
    private fun generateReport(): String {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        
        sb.appendLine("=== WeatherWall Debug Trace ===")
        sb.appendLine("Session: ${dateFormat.format(Date(sessionStartTime))}")
        sb.appendLine("Entries: ${entries.size}")
        sb.appendLine()
        
        // Calculate timing statistics
        val timings = entries.filter { it.durationNanos != null }
            .groupBy { it.label }
        
        if (timings.isNotEmpty()) {
            sb.appendLine("=== Timing Summary ===")
            for ((label, labelEntries) in timings) {
                val durations = labelEntries.mapNotNull { it.durationNanos }
                val count = durations.size
                val totalMs = durations.sum() / 1_000_000.0
                val avgMs = totalMs / count
                val maxMs = (durations.maxOrNull() ?: 0) / 1_000_000.0
                sb.appendLine("$label: count=$count, total=${String.format("%.2f", totalMs)}ms, avg=${String.format("%.2f", avgMs)}ms, max=${String.format("%.2f", maxMs)}ms")
            }
            sb.appendLine()
        }
        
        sb.appendLine("=== Event Log ===")
        for (entry in entries) {
            val time = dateFormat.format(Date(entry.timestamp))
            when {
                entry.durationNanos != null -> {
                    val ms = entry.durationNanos / 1_000_000.0
                    sb.appendLine("[$time] ${entry.label}: ${String.format("%.2f", ms)}ms")
                }
                entry.message != null -> {
                    sb.appendLine("[$time] ${entry.label}: ${entry.message}")
                }
            }
        }
        
        return sb.toString()
    }
}
