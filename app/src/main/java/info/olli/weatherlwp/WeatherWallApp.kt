package info.olli.weatherlwp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class WeatherWallApp : Application() {
    
    companion object {
        private const val TAG = "WeatherWallApp"
        @Volatile
        private var instance: WeatherWallApp? = null
        
        @Volatile
        private var lastCrashLog: String? = null
        
        fun getLastCrashLog(): String? = lastCrashLog
        
        fun copyToClipboard(context: Context, text: String, label: String = "Crash Log") {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(label, text)
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
        
        // Install crash handler
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }
        
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
            
            // Try to copy to clipboard
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
