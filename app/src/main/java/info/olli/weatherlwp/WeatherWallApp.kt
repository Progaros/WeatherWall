package info.olli.weatherlwp

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class WeatherWallApp : Application() {
    
    companion object {
        private const val TAG = "WeatherWallApp"
        private const val CRASH_LOG_FILE = "crash_log.txt"
        
        @Volatile
        private var instance: WeatherWallApp? = null
        
        @Volatile
        private var lastCrashLog: String? = null
        
        fun getLastCrashLog(): String? = lastCrashLog
        
        /**
         * Load last crash log from file (survives process death).
         * Call from Activity.onCreate() to retrieve crash info after restart.
         */
        fun loadCrashLogFromFile(context: Context): String? {
            return try {
                val file = File(context.filesDir, CRASH_LOG_FILE)
                if (file.exists()) file.readText() else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read crash log file", e)
                null
            }
        }
        
        /**
         * Clear persisted crash log after it has been shown/handled.
         */
        fun clearCrashLogFile(context: Context) {
            try {
                val file = File(context.filesDir, CRASH_LOG_FILE)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete crash log file", e)
            }
        }
        
        fun copyToClipboard(context: Context, text: String, label: String = "Crash Log") {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label, text)
                // Mark as sensitive on Android 13+ so it doesn't appear in clipboard preview
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                Log.d(TAG, "Copied to clipboard: $label")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy to clipboard", e)
            }
        }
        
        fun getInstance(): WeatherWallApp? = instance
    }
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Load any crash log from previous session
        lastCrashLog = loadCrashLogFromFile(this)
        
        // Install crash handler
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }

        runCatching { info.olli.weatherlwp.settings.SettingsResetManager.ensureDefaults(this) }
            .onFailure { Log.e(TAG, "Failed to apply preference defaults", it) }
        
        Log.d(TAG, "WeatherWall Application initialized with crash handler")
    }
    
    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            
            val crashLog = buildString {
                appendLine("=== WeatherWall Crash Log ===")
                appendLine("Thread: ${thread.name}")
                appendLine("Time: ${System.currentTimeMillis()}")
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("=== Stack Trace ===")
                appendLine(sw.toString())
                appendLine()
                appendLine("=== Device Info ===")
                appendLine("Android: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
            
            lastCrashLog = crashLog
            Log.e(TAG, crashLog)
            
            // Write crash log to file (reliable — no Binder IPC, survives process death)
            try {
                val file = File(filesDir, CRASH_LOG_FILE)
                file.writeText(crashLog)
            } catch (e: Exception) {
                Log.e(TAG, "Could not write crash log to file", e)
            }
            
            // Best-effort clipboard copy (may fail during crash due to Binder)
            try {
                copyToClipboard(this, crashLog, "WeatherWall Crash")
            } catch (e: Exception) {
                // Clipboard may not be available during crash
                Log.e(TAG, "Could not copy crash log to clipboard", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // Call default handler to show system crash dialog
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
